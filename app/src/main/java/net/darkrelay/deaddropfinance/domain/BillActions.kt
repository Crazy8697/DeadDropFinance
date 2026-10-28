package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BillPaymentEntity
import net.darkrelay.deaddropfinance.data.BillRecurrence
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MetaEntity
import net.darkrelay.deaddropfinance.data.NotifAppEntity
import net.darkrelay.deaddropfinance.data.PaymentMethod
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.SubscriptionEntity
import net.darkrelay.deaddropfinance.data.SubscriptionStatus
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.TransactionSource

/**
 * Everything the bill, payday, subscription and notification screens change. Each write goes through BudgetStore with
 * its change log rows (the notification apps table is this phone's own and isn't logged).
 */
class BillActions(
    private val store: BudgetStore,
    private val actions: BudgetActions = BudgetActions(store),
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val dao get() = store.dao

    // ---- bills ----------------------------------------------------------------------------------------------------

    data class BillDraft(
        val name: String,
        val amount: Long,
        val due: LocalDate?,
        val recurrence: BillRecurrence = BillRecurrence.MONTHLY,
        val envelopeId: String? = null,
        val autopay: Boolean = false,
        val reminder: Boolean = false,
        val reminderDays: Int = 2,
        val matchText: String? = null,
        val anyAmount: Boolean = false,
        val varies: Boolean = false,
    )

    suspend fun saveBill(id: String?, d: BillDraft): String {
        if (d.name.isBlank()) throw BudgetRuleException("Give the bill a name.")
        if (d.amount < 0) throw BudgetRuleException("The amount can't be negative.")
        if (d.due == null) throw BudgetRuleException("Enter when it's due.")
        if (d.reminderDays !in 0..30) throw BudgetRuleException("Remind 0 to 30 days ahead.")
        val words = d.matchText?.let { Payees.words(it) }?.takeIf { it.isNotEmpty() }
        return store.write {
            val old = id?.let { dao.bill(it) }
            val base = old ?: BillEntity(name = "", envelopeId = null, amountDue = 0, dueDate = null, createdAt = now, updatedAt = now)
            put(
                old,
                base.copy(
                    name = d.name.trim(), amountDue = d.amount, dueDate = d.due, recurrence = d.recurrence, envelopeId = d.envelopeId,
                    autopay = d.autopay, reminder = d.reminder, reminderDays = d.reminderDays, matchText = words, anyAmount = d.anyAmount,
                    varies = d.varies, updatedAt = now,
                ),
            ).id
        }
    }

    /** Deletes a bill and its payments (tombstones); transactions stay as they are. */
    suspend fun deleteBill(id: String) = store.write {
        val b = dao.bill(id) ?: return@write
        dao.billPaymentsNow().filter { it.billId == id }.forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
        put(b, b.copy(deleted = true, updatedAt = now))
        dao.subscriptionsNow().filter { it.billId == id }.forEach { put(it, it.copy(billId = null, updatedAt = now)) }
    }

    private suspend fun cycle(billId: String, due: LocalDate): Bills.Cycle {
        val b = dao.bill(billId) ?: throw BudgetRuleException("That bill is gone.")
        return Bills.cycles(b, dao.billPaymentsNow(), due).lastOrNull { it.due == due } ?: throw BudgetRuleException("That bill isn't due then.")
    }

    /**
     * Ticks a bill's cycle paid by hand (the rest of it). [cashAccountId]: paid in cash, which also logs the cash spend
     * from the bill's envelope, so the Cash account and envelope go down.
     */
    suspend fun markPaid(billId: String, due: LocalDate, cashAccountId: String? = null) {
        val c = cycle(billId, due)
        val left = c.remaining.takeIf { it > 0 } ?: if (c.expected == 0L && c.payments.isEmpty()) 0L else return
        store.write {
            var txId: String? = null
            if (cashAccountId != null && left > 0) {
                val t = put(
                    null,
                    TransactionEntity(
                        accountId = cashAccountId, date = today(), payee = c.bill.name, memo = "Paid in cash", amount = -left,
                        sortedBy = SortedBy.HAND, sortedAt = now, createdAt = now, updatedAt = now,
                    ),
                )
                put(
                    null,
                    TransactionLineEntity(
                        transactionId = t.id, bucket = if (c.bill.envelopeId != null) LineBucket.ENVELOPE else LineBucket.UNSORTED,
                        envelopeId = c.bill.envelopeId, amount = -left, createdAt = now, updatedAt = now,
                    ),
                )
                txId = t.id
            }
            put(
                null,
                BillPaymentEntity(
                    billId = billId, transactionId = txId, amount = left, date = today(), periodDue = due,
                    method = if (cashAccountId != null) PaymentMethod.CASH else PaymentMethod.HAND, createdAt = now, updatedAt = now,
                ),
            )
        }
    }

    /** Unticks a cycle: the payments ticked by hand go (bank and cash ones stay: they're real money). */
    suspend fun unmarkPaid(billId: String, due: LocalDate) {
        val c = cycle(billId, due)
        store.write { c.payments.filter { it.method == PaymentMethod.HAND }.forEach { put(it, it.copy(deleted = true, updatedAt = now)) } }
    }

    /**
     * A partly paid cycle's rest goes to a Debt envelope (the bill's catch-up envelope, made the first time): its
     * balance owed goes up by the rest, and the cycle is settled.
     */
    suspend fun moveRestToDebt(billId: String, due: LocalDate): String {
        val c = cycle(billId, due)
        val rest = c.remaining
        if (rest <= 0) throw BudgetRuleException("Nothing is left owing on it.")
        val b = actions.snapshot()
        return store.write {
            val name = "${c.bill.name} catch-up"
            val existing = dao.envelopesNow().firstOrNull { it.mode == EnvelopeMode.DEBT && it.name.equals(name, ignoreCase = true) && !it.archived }
            val env = if (existing != null) {
                val owed = (b.debtRemaining(existing) ?: 0L) + rest
                put(existing, existing.copy(debtOwed = owed, debtOwedSince = today(), updatedAt = now))
            } else {
                put(
                    null,
                    EnvelopeEntity(
                        name = name, mode = EnvelopeMode.DEBT, debtOwed = rest, debtOwedSince = today(),
                        sortOrder = (dao.envelopesNow().maxOfOrNull { it.sortOrder } ?: 0) + 1, createdAt = now, updatedAt = now,
                    ),
                )
            }
            put(null, BillPaymentEntity(billId = billId, transactionId = null, amount = rest, date = today(), periodDue = due, method = PaymentMethod.DEBT, createdAt = now, updatedAt = now))
            env.id
        }
    }

    /**
     * CSV import and notifications: [txIds] that pay a bill are counted toward its open cycle. A cycle
     * already ticked paid by hand takes the transaction instead of a second payment. Returns how many were counted.
     */
    suspend fun matchPayments(txIds: Collection<String>): Int {
        if (txIds.isEmpty()) return 0
        val bills = dao.billsNow()
        if (bills.isEmpty()) return 0
        val rules = dao.rulesNow()
        var payments = dao.billPaymentsNow()
        var n = 0
        for (id in txIds) {
            val t = dao.transaction(id) ?: continue
            if (payments.any { it.transactionId == t.id }) continue
            val bill = bills.filter { Bills.matches(it, t, rules, payments, t.date) }
                .minByOrNull { b -> Bills.openCycle(b, payments, t.date)?.due?.let { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it, t.date)) } ?: Long.MAX_VALUE }
                ?: continue
            val open = Bills.openCycle(bill, payments, t.date)
            val lastHand = Bills.cycles(bill, payments, t.date.plusDays(20)).lastOrNull { c ->
                c.isPaid && c.payments.any { it.method == PaymentMethod.HAND && it.transactionId == null }
            }
            store.write {
                val hand = lastHand?.payments?.firstOrNull { it.method == PaymentMethod.HAND && it.transactionId == null }
                if (hand != null && (open == null || open.due.isAfter(lastHand.due))) {
                    put(hand, hand.copy(transactionId = t.id, method = PaymentMethod.BANK, amount = -t.amount, date = t.date, updatedAt = now))
                } else {
                    put(null, BillPaymentEntity(billId = bill.id, transactionId = t.id, amount = -t.amount, date = t.date, periodDue = open?.due, method = PaymentMethod.BANK, createdAt = now, updatedAt = now))
                }
            }
            payments = dao.billPaymentsNow()
            n++
        }
        return n
    }

    // ---- paydays ----------------------------------------------------------------------------------------------------

    suspend fun paydayCorrection(): LocalDate? = dao.meta(META_PAYDAY)?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The user's own date for the next payday (null: back to the prediction). */
    suspend fun correctPayday(date: LocalDate?) = dao.putMeta(MetaEntity(META_PAYDAY, date?.toString() ?: ""))

    suspend fun prediction(): Paydays.Prediction = Paydays.predict(actions.snapshot().paydayDates, today(), paydayCorrection())

    // ---- subscriptions ----------------------------------------------------------------------------------------------

    private suspend fun upsertSub(s: Recurring.Series, change: (SubscriptionEntity) -> SubscriptionEntity) = store.write {
        val old = dao.subscriptionsNow().firstOrNull { s.key in it.keys }
        val base = old ?: SubscriptionEntity(matchKey = s.keys.joinToString(";"), createdAt = now, updatedAt = now)
        put(old, change(base).copy(updatedAt = now)).id
    }

    suspend fun confirm(s: Recurring.Series) = upsertSub(s) { it.copy(status = SubscriptionStatus.CONFIRMED) }
    suspend fun dismiss(s: Recurring.Series) = upsertSub(s) { it.copy(status = SubscriptionStatus.DISMISSED) }
    suspend fun restore(s: Recurring.Series) = upsertSub(s) { it.copy(status = SubscriptionStatus.CONFIRMED) }
    suspend fun rename(s: Recurring.Series, name: String) = upsertSub(s) { it.copy(name = name.trim(), status = if (it.status == SubscriptionStatus.NEW) SubscriptionStatus.CONFIRMED else it.status) }

    /** Merges [other] into [s] (the same subscription under two payee names): one series from then on. */
    suspend fun merge(s: Recurring.Series, other: Recurring.Series) {
        store.write {
            val subs = dao.subscriptionsNow()
            val a = subs.firstOrNull { s.key in it.keys }
            val b = subs.firstOrNull { other.key in it.keys }
            val keys = (s.keys + other.keys).distinct().joinToString(";")
            val base = a ?: SubscriptionEntity(matchKey = keys, createdAt = now, updatedAt = now)
            put(a, base.copy(matchKey = keys, status = SubscriptionStatus.CONFIRMED, updatedAt = now))
            if (b != null && b.id != base.id) put(b, b.copy(deleted = true, updatedAt = now))
        }
    }

    /** A recurring charge becomes a bill (its payee and latest amount, due on its next date), linked to it. */
    suspend fun makeBill(s: Recurring.Series, envelopeId: String?): String {
        val rec = when (s.interval) {
            Recurring.Interval.MONTHLY -> BillRecurrence.MONTHLY
            Recurring.Interval.QUARTERLY -> BillRecurrence.QUARTERLY
            Recurring.Interval.SEMIANNUAL -> BillRecurrence.SEMIANNUAL
            Recurring.Interval.YEARLY -> BillRecurrence.YEARLY
        }
        val id = saveBill(null, BillDraft(s.name, s.amount, s.nextDate, rec, envelopeId, matchText = s.key))
        upsertSub(s) { it.copy(billId = id, status = SubscriptionStatus.CONFIRMED) }
        return id
    }

    // ---- notification apps (this phone's own) ----------------------------------------------------------------------

    suspend fun pickApp(pkg: String, label: String, accountId: String?) {
        val old = dao.notifApp(pkg)
        dao.upsertNotifApp(old?.copy(label = label, accountId = accountId, enabled = true) ?: NotifAppEntity(pkg, label, accountId))
    }

    suspend fun dropApp(pkg: String) = dao.deleteNotifApp(pkg)

    suspend fun teach(pkg: String, t: NotifParse.Template) {
        val a = dao.notifApp(pkg) ?: return
        val ts = NotifParse.decode(a.templates).filter { it.deposit != t.deposit } + t
        dao.upsertNotifApp(a.copy(templates = NotifParse.encode(ts)))
    }

    suspend fun forget(pkg: String) { dao.notifApp(pkg)?.let { dao.upsertNotifApp(it.copy(templates = "")) } }

    companion object {
        const val META_PAYDAY = "payday.next"
    }
}

/**
 * A parsed bank notification becomes a pending transaction right away (FEATURES.md, Live spend alerts): money out in
 * To sort with its suggestion (or a bought parked buy's charge), or a deposit, an ordinary transaction (payday is
 * marked by hand only, 1.3.1). A CSV import later matches the real record to it (never a second one). Only the parsed
 * result is kept.
 */
class NotifLog(
    private val store: BudgetStore,
    private val actions: BudgetActions = BudgetActions(store),
    private val today: () -> LocalDate = { LocalDate.now() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Logged(val txId: String?, val duplicate: Boolean, val noAccount: Boolean = false)

    suspend fun log(pkg: String, p: NotifParse.Parsed): Logged {
        val dao = store.dao
        val app = dao.notifApp(pkg) ?: return Logged(null, duplicate = false, noAccount = true)
        val account = app.accountId?.let { dao.account(it) }?.takeIf { !it.deleted } ?: return Logged(null, duplicate = false, noAccount = true)
        val signed = if (p.deposit) p.amount else -p.amount
        val payee = p.payee.ifBlank { app.label }
        val now = clock()
        // the same notification posted twice (an update, a repeat) is logged once
        val dup = dao.transactionsNow().any {
            it.source == TransactionSource.NOTIFICATION && it.accountId == account.id && it.amount == signed &&
                Payees.clean(it.payee) == Payees.clean(payee) && now - it.createdAt < DUPLICATE_MS
        }
        dao.upsertNotifApp(app.copy(lastAmount = p.amount, lastPayee = payee, lastDeposit = p.deposit, lastAt = now))
        if (dup) return Logged(null, duplicate = true)
        val id = store.write {
            val t = put(
                null,
                TransactionEntity(
                    accountId = account.id, date = today(), payee = payee, amount = signed, pending = true, importDescription = p.payee.ifBlank { null },
                    source = TransactionSource.NOTIFICATION, createdAt = now, updatedAt = now,
                ),
            )
            put(null, TransactionLineEntity(transactionId = t.id, bucket = if (account.onBudget) LineBucket.UNSORTED else LineBucket.OFF_BUDGET, amount = signed, createdAt = now, updatedAt = now))
            t.id
        }
        // a deposit into the budget is an ordinary transaction; anything else may be a parked buy's charge or a bill payment
        if (!(p.deposit && account.onBudget)) {
            ParkedBuys(store, actions, today, clock).matchCharges(listOf(id))
            BillActions(store, actions, today).matchPayments(listOf(id))
        }
        return Logged(id, duplicate = false)
    }

    /** A notification entry the bank never brought in, kept as real: it stops being pending (and flagged). */
    suspend fun keep(txId: String) = store.write {
        val t = store.dao.transaction(txId) ?: return@write
        if (t.pending) put(t, t.copy(pending = false, updatedAt = now))
    }

    companion object {
        const val DUPLICATE_MS = 10L * 60 * 1000
        /** A logged notification no import has matched after this many days is flagged. */
        const val STALE_DAYS = 7L

        fun stale(b: Budget, today: LocalDate): List<TransactionEntity> = b.transactions.filter {
            it.source == TransactionSource.NOTIFICATION && it.pending && java.time.temporal.ChronoUnit.DAYS.between(it.date, today) >= STALE_DAYS
        }
    }
}
