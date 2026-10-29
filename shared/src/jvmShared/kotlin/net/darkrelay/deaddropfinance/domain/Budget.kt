package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BillPaymentEntity
import net.darkrelay.deaddropfinance.data.CategoryEntity
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.data.TransactionLineEntity

/**
 * All of the budget's math, from the rows alone (tombstones already filtered out). No screen does arithmetic on
 * money; it asks this. Everything is in cents, so nothing is ever rounded.
 *
 * The money always balances: the sum of the budget accounts' balances equals Ready to Assign plus every envelope's
 * balance plus whatever is still in To sort plus the held amounts (sortedCheck).
 *
 * Accounts outside the budget (archived, or track only) don't count at all: as if they were never there. Money
 * moved between a budget account and one outside it comes out of (or goes into) Ready to Assign.
 *
 * An account's starting balance is its balance as of its start date, so it already holds everything before that
 * date. A transaction dated before its account's start date (imported history, say) is shown but counts nowhere:
 * not in the balance and not in the budget. Counting it again was the bug behind a synced account's wrong balance.
 */
class Budget(
    val accounts: List<AccountEntity>,
    val envelopes: List<EnvelopeEntity>,
    val transactions: List<TransactionEntity>,
    val lines: List<TransactionLineEntity>,
    val moves: List<MoveEntity>,
    val bills: List<BillEntity> = emptyList(),
    val billPayments: List<BillPaymentEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    /**
     * The budget start date (1.1.1): transactions before it are bank history. They stay in the account balances and in
     * Categories, but never count in envelopes, Ready to Assign, Safe to Spend or the assigned total. Null: none set.
     */
    val budgetStart: LocalDate? = null,
) {
    private val txById = transactions.associateBy { it.id }

    /** Accounts whose money isn't part of the budget (archived or track only). */
    val offBudgetIds: Set<String> = accounts.filter { !it.onBudget }.map { it.id }.toSet()

    fun isOnBudget(accountId: String) = accountId !in offBudgetIds

    private val startOf: Map<String, LocalDate> = accounts.associate { it.id to it.startDate }

    /** A transaction on [date] counts in [accountId]'s balance: it isn't before the account's starting balance date. */
    fun countsForBalance(accountId: String, date: LocalDate): Boolean = startOf[accountId]?.let { !date.isBefore(it) } ?: true

    /**
     * A transaction on [date] counts for the budget in [accountId]: it isn't before the account's starting balance date
     * or the budget start date (the later one wins).
     */
    fun countsFor(accountId: String, date: LocalDate): Boolean =
        countsForBalance(accountId, date) && (budgetStart == null || !date.isBefore(budgetStart))

    /**
     * History that counts nowhere in the budget: before the account's starting balance (already inside it) or before the
     * budget start (paid for before budgeting began). Shown, never in To sort.
     */
    fun beforeStart(t: TransactionEntity): Boolean = (t.kind == TransactionKind.NORMAL || t.kind == TransactionKind.BONUS) && !countsFor(t.accountId, t.date)

    /** Before the account's own starting balance date. */
    fun beforeAccountStart(t: TransactionEntity): Boolean = t.kind == TransactionKind.NORMAL && !countsForBalance(t.accountId, t.date)

    /** After the account's starting balance date but before the budget start: shown as before the budget start. */
    fun beforeBudgetStart(t: TransactionEntity): Boolean =
        budgetStart != null && t.date.isBefore(budgetStart) && (t.kind == TransactionKind.BONUS || countsForBalance(t.accountId, t.date))

    /** Lines that count for the budget: of live transactions in budget accounts, not washes, not history. */
    private val budgetLines = lines.filter { l ->
        txById[l.transactionId]?.let { !it.isWash && isOnBudget(it.accountId) && countsFor(it.accountId, it.date) } == true
    }

    private fun balanceOf(a: net.darkrelay.deaddropfinance.data.AccountEntity, postedOnly: Boolean, through: LocalDate? = null): Long {
        var b = a.startingBalance
        for (t in transactions) {
            if (through != null && t.date.isAfter(through)) continue
            when {
                t.kind == TransactionKind.TRANSFER && t.accountId == a.id && countsForBalance(a.id, t.date) -> b -= t.amount
                t.kind == TransactionKind.TRANSFER && t.transferAccountId == a.id && countsForBalance(a.id, t.date) -> b += t.amount
                t.kind == TransactionKind.NORMAL && t.accountId == a.id && countsForBalance(a.id, t.date) && !(postedOnly && t.pending) -> b += t.amount
            }
        }
        return b
    }

    val accountBalances: Map<String, Long> = accounts.associate { a -> a.id to balanceOf(a, postedOnly = false) }

    /**
     * What an account brings into the budget: its starting balance, or with a later budget start, its balance at the
     * start of that day (everything before it already happened, so it's already in the money there is).
     */
    fun openingBalance(a: AccountEntity): Long {
        val s = budgetStart ?: return a.startingBalance
        if (!s.isAfter(a.startDate)) return a.startingBalance
        return balanceOf(a, postedOnly = false, through = s.minusDays(1))
    }

    /** The balance with pending rows left out: what a bank's own balance is compared with. */
    fun postedBalance(accountId: String): Long = accounts.firstOrNull { it.id == accountId }?.let { balanceOf(it, postedOnly = true) } ?: 0L

    /** The posted balance at the end of [date]: what a bank's balance as of that date is compared with. */
    fun postedBalanceOn(accountId: String, date: LocalDate): Long =
        accounts.firstOrNull { it.id == accountId }?.let { balanceOf(it, postedOnly = true, through = date) } ?: 0L

    val envelopeBalances: Map<String, Long> = envelopes.associate { e ->
        var b = 0L
        for (l in budgetLines) if (l.bucket == LineBucket.ENVELOPE && l.envelopeId == e.id) b += l.amount
        for (m in moves) {
            if (m.toEnvelopeId == e.id) b += m.amount
            if (m.fromEnvelopeId == e.id) b -= m.amount
        }
        e.id to b
    }

    /**
     * What each budget account holds back: its held amount, but never more than its balance (and nothing when the
     * balance is zero or below), so a held amount can't push Ready to Assign below what the money really is.
     */
    val held: Map<String, Long> = accounts.filter { it.onBudget && it.heldAmount > 0 }.associate { a ->
        a.id to minOf(a.heldAmount, maxOf(accountBalances[a.id] ?: 0L, 0L))
    }

    val heldTotal: Long get() = held.values.sum()

    /** A budget account whose balance is below its held amount. */
    fun heldShort(accountId: String): Boolean {
        val a = accounts.firstOrNull { it.id == accountId } ?: return false
        return a.onBudget && a.heldAmount > 0 && (accountBalances[a.id] ?: 0L) < a.heldAmount
    }

    /**
     * Money not in any envelope yet: budget accounts' starting balances and income lines, minus what was assigned
     * out of it, minus transfers out of the budget (plus transfers into it), minus held amounts. A line marked as a
     * transfer between the user's own accounts (TRANSFER) goes out of or into it too: its other side, synced into
     * another budget account, puts it back.
     */
    val readyToAssign: Long = run {
        var r = accounts.filter { it.onBudget }.sumOf { openingBalance(it) }
        for (l in budgetLines) if (l.bucket == LineBucket.READY_TO_ASSIGN || l.bucket == LineBucket.OFF_BUDGET || l.bucket == LineBucket.TRANSFER) r += l.amount
        for (m in moves) {
            if (m.fromEnvelopeId == null) r -= m.amount
            if (m.toEnvelopeId == null) r += m.amount
        }
        for (t in transactions) {
            if (t.kind != TransactionKind.TRANSFER) continue
            // each side counts when its account is in the budget and the transfer isn't before its starting balance
            val from = isOnBudget(t.accountId) && countsFor(t.accountId, t.date)
            val to = t.transferAccountId?.let { isOnBudget(it) && countsFor(it, t.date) } ?: false
            if (from && !to) r -= t.amount
            if (!from && to) r += t.amount
        }
        r - held.values.sum()
    }

    /** Bonus income: money received outside any account (Cash, a gift card, Venmo). It sits in Ready to Assign through its line. */
    val bonusTotal: Long = transactions.filter { it.kind == TransactionKind.BONUS && countsFor(it.accountId, it.date) }.sumOf { it.amount }

    /** What the budget holds overall: the budget accounts' balances plus bonus income. */
    val overallBalance: Long get() = accountBalances.filterKeys { isOnBudget(it) }.values.sum() + bonusTotal

    /** Signed total of lines nobody has sorted yet (To sort): spending shows negative, income positive. */
    val toSortTotal: Long = budgetLines.filter { it.bucket == LineBucket.UNSORTED }.sumOf { it.amount }

    /** Transactions with at least one unsorted line, newest first. */
    val toSort: List<TransactionEntity> = run {
        val ids = budgetLines.filter { it.bucket == LineBucket.UNSORTED }.map { it.transactionId }.toSet()
        transactions.filter { it.id in ids }.sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.createdAt })
    }

    /** sum(budget accounts) + bonus income - (RTA + envelopes + To sort + held): always 0 (washes net to zero in pairs). */
    val sortedCheck: Long
        get() = overallBalance - (readyToAssign + envelopeBalances.values.sum() + toSortTotal + heldTotal) -
            transactions.filter { it.isWash && it.kind == TransactionKind.NORMAL && isOnBudget(it.accountId) && countsFor(it.accountId, it.date) }.sumOf { it.amount }

    fun envelopeBalance(id: String) = envelopeBalances[id] ?: 0L

    /**
     * One row of an envelope's Activity list (1.2.3): a spending line (with its [transactionId]) or a money move.
     * [amount] is signed for this envelope: money in is positive, money out negative. [counts] is false for a line the
     * budget leaves out (before the budget start, a wash, history); every move counts.
     */
    data class EnvelopeActivity(
        val id: String, val date: LocalDate, val createdAt: Long, val label: String, val amount: Long,
        val transactionId: String? = null, val beforeBudgetStart: Boolean = false, val counts: Boolean = true,
    )

    /** Everything in and out of an envelope, newest first; same date by time, then id. The counted rows sum to its balance. */
    fun envelopeActivity(envelopeId: String): List<EnvelopeActivity> {
        val counted = budgetLines.mapTo(HashSet()) { it.id }
        val names = envelopes.associate { it.id to it.name }
        fun name(id: String?) = names[id] ?: "a deleted envelope"
        val rows = mutableListOf<EnvelopeActivity>()
        for (l in lines) {
            if (l.bucket != LineBucket.ENVELOPE || l.envelopeId != envelopeId) continue
            val t = txById[l.transactionId] ?: continue
            rows += EnvelopeActivity(l.id, t.date, l.createdAt, t.payee.ifBlank { "No description" }, l.amount, t.id, beforeBudgetStart(t), l.id in counted)
        }
        for (m in moves) {
            val into = m.toEnvelopeId == envelopeId
            val out = m.fromEnvelopeId == envelopeId
            if (into == out) continue
            val label = when {
                m.kind == MoveKind.AUTOFILL && into -> "Payday auto-fill"
                m.kind == MoveKind.SWEEP && out -> "Rollover sweep"
                m.kind == MoveKind.SWEEP && into -> "From rollover sweep"
                into && m.fromEnvelopeId == null -> if (m.kind == MoveKind.ASSIGN) "Assigned" else "Moved from Ready to Assign"
                out && m.toEnvelopeId == null -> "Moved to Ready to Assign"
                into -> "Moved in from ${name(m.fromEnvelopeId)}"
                else -> "Moved out to ${name(m.toEnvelopeId)}"
            }
            rows += EnvelopeActivity(m.id, m.date, m.createdAt, label, if (into) m.amount else -m.amount)
        }
        return rows.sortedWith(compareByDescending<EnvelopeActivity> { it.date }.thenByDescending { it.createdAt }.thenByDescending { it.id })
    }

    /** The envelopes Safe to Spend counts: marked everyday, not archived, and never Rollover. In fill order. */
    val everydayEnvelopes: List<EnvelopeEntity> get() = fillOrder.filter { it.everyday && it.mode != EnvelopeMode.ROLLOVER }

    /** What's left in the everyday envelopes (Safe to Spend), or null when no envelope is marked everyday. */
    val safeToSpend: Long? get() = everydayEnvelopes.takeIf { it.isNotEmpty() }?.sumOf { envelopeBalance(it.id) }

    /** Money given back to a purchase by the returns confirmed against it (positive cents). */
    fun returnedOf(purchaseId: String): Long = transactions.filter { it.returnOf == purchaseId }.sumOf { it.amount }
    fun accountBalance(id: String) = accountBalances[id] ?: 0L

    val rollover: EnvelopeEntity? get() = envelopes.firstOrNull { it.mode == EnvelopeMode.ROLLOVER }
    val cash: EnvelopeEntity? get() = envelopes.firstOrNull { it.mode == EnvelopeMode.CASH && !it.archived }

    /** Envelopes in fill order (Rollover included, archived left out). */
    val fillOrder: List<EnvelopeEntity> get() = envelopes.filter { !it.archived }.sortedBy { it.sortOrder }

    // ---- categories (1.1.0) ----------------------------------------------------------------------------------

    /** The built-in Unplanned envelope, where a purchase in a category no envelope claims goes. */
    val unplannedEnvelope: EnvelopeEntity? get() = envelopes.firstOrNull { it.unplanned && !it.archived }

    fun category(id: String?): CategoryEntity? = id?.let { i -> categories.firstOrNull { it.id == i } }

    fun categoryName(id: String?): String = category(id)?.name ?: UNCATEGORIZED

    /** Where a purchase in [categoryId] goes: the envelope that claims it, or Unplanned. Null only if neither exists. */
    fun envelopeForCategory(categoryId: String): String? {
        val claim = category(categoryId)?.envelopeId?.takeIf { id -> envelopes.any { it.id == id && !it.archived } }
        return claim ?: unplannedEnvelope?.id
    }

    /** The categories an envelope claims. */
    fun claimsOf(envelopeId: String): List<CategoryEntity> = categories.filter { it.envelopeId == envelopeId }.sortedBy { it.sortOrder }

    /**
     * A budget month: payday to payday. [start] null: before the first payday; [end] null: the open month (the one
     * now). With no paydays at all there is one month, with neither.
     */
    data class Month(val start: LocalDate?, val end: LocalDate?) {
        fun contains(d: LocalDate) = (start == null || !d.isBefore(start)) && (end == null || d.isBefore(end))
    }

    /** Budget months newest first: the open month, then each closed one back to before the first payday (when anything is). */
    fun months(today: LocalDate): List<Month> {
        val p = paydayDates.filter { !it.isAfter(today) }
        if (p.isEmpty()) return listOf(Month(null, null))
        val out = mutableListOf(Month(p.last(), null))
        for (i in p.indices.reversed().drop(1)) out += Month(p[i], p[i + 1])
        val first = p.first()
        if (transactions.any { it.date.isBefore(first) } || moves.any { it.date.isBefore(first) }) out += Month(null, first)
        return out
    }

    /** What a purchase line adds to its category's spending (positive: spent; a return's line is negative). */
    /** Lines Categories counts: as the budget's, but history before the budget start counts too, in its own budget month. */
    private val categoryLines = lines.filter { l ->
        txById[l.transactionId]?.let { !it.isWash && isOnBudget(it.accountId) && countsForBalance(it.accountId, it.date) } == true
    }

    private fun spendLines(m: Month): List<Pair<net.darkrelay.deaddropfinance.data.TransactionLineEntity, TransactionEntity>> =
        categoryLines.mapNotNull { l ->
            val t = txById[l.transactionId] ?: return@mapNotNull null
            if (t.kind != TransactionKind.NORMAL || !m.contains(t.date)) return@mapNotNull null
            if (!(t.amount < 0 || t.returnOf != null)) return@mapNotNull null
            if (l.bucket != LineBucket.ENVELOPE && l.bucket != LineBucket.UNSORTED) return@mapNotNull null
            l to t
        }

    /** Spending per category in [m], biggest first; the key null is Uncategorized. Returns take their amount back off. */
    fun categoryTotals(m: Month): List<Pair<CategoryEntity?, Long>> =
        spendLines(m).groupBy { (l, _) -> category(l.categoryId) }.map { (c, ls) -> c to -ls.sumOf { it.first.amount } }
            .filter { it.second != 0L }.sortedWith(compareByDescending<Pair<CategoryEntity?, Long>> { it.second }.thenBy { it.first?.name ?: "" })

    fun categoryTotal(m: Month): Long = categoryTotals(m).sumOf { it.second }

    /** The purchases (and returns) behind one category's total in [m], newest first, each with its amount in that category. */
    fun categoryPurchases(m: Month, categoryId: String?): List<Pair<TransactionEntity, Long>> =
        spendLines(m).filter { (l, _) -> category(l.categoryId)?.id == categoryId }
            .groupBy { it.second }.map { (t, ls) -> t to -ls.sumOf { it.first.amount } }
            .sortedWith(compareByDescending<Pair<TransactionEntity, Long>> { it.first.date }.thenByDescending { it.first.createdAt })

    /** Income put straight into an envelope in [m] (1.1.1): money in sorted to an envelope, never a return. */
    private fun envelopeIncomeIn(m: Month): Long = budgetLines.filter { l ->
        l.bucket == LineBucket.ENVELOPE && l.amount > 0 &&
            txById[l.transactionId]?.let { it.kind == TransactionKind.NORMAL && it.amount > 0 && it.returnOf == null && m.contains(it.date) } == true
    }.sumOf { it.amount }

    /**
     * Income that came in during [m]: money into Ready to Assign (the payday deposit, other deposits, bonus income) and
     * income put straight into an envelope. A return isn't income.
     */
    fun incomeIn(m: Month): Long = budgetLines.filter { l ->
        l.bucket == LineBucket.READY_TO_ASSIGN && l.amount > 0 && txById[l.transactionId]?.let { m.contains(it.date) } == true
    }.sumOf { it.amount } + envelopeIncomeIn(m)

    /**
     * What was assigned to envelopes in [m]: out of Ready to Assign, less what was moved back into it, plus income put
     * straight into an envelope. A Rollover sweep (envelope to Rollover) and a return change neither figure.
     */
    fun assignedIn(m: Month): Long = moves.filter { m.contains(it.date) }.sumOf { mv ->
        when {
            mv.fromEnvelopeId == null && mv.toEnvelopeId != null -> mv.amount
            mv.toEnvelopeId == null && mv.fromEnvelopeId != null -> -mv.amount
            else -> 0L
        }
    } + envelopeIncomeIn(m)

    // ---- budget months: payday to payday ---------------------------------------------------------------------

    /** The dates of the deposits marked as payday, oldest first. */
    val paydayDates: List<LocalDate> get() = transactions.filter { it.isPayday }.map { it.date }.distinct().sorted()

    /** Average days between paydays (the last six gaps); 14 when there aren't two paydays yet. */
    fun paydayIntervalDays(): Long {
        val d = paydayDates.takeLast(7)
        if (d.size < 2) return 14
        val gaps = d.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.filter { it > 0 }
        return if (gaps.isEmpty()) 14 else maxOf(1, gaps.sum() / gaps.size)
    }

    /** The closed budget months: [start, end) between consecutive paydays. */
    fun closedMonths(): List<Pair<LocalDate, LocalDate>> = paydayDates.zipWithNext()

    /** Money spent from an envelope in each closed budget month (positive cents). */
    fun spentPerClosedMonth(envelopeId: String): List<Long> = closedMonths().map { (from, to) ->
        -budgetLines.filter { l ->
            l.bucket == LineBucket.ENVELOPE && l.envelopeId == envelopeId && l.amount < 0 &&
                txById[l.transactionId]!!.date.let { !it.isBefore(from) && it.isBefore(to) }
        }.sumOf { it.amount }
    }

    /**
     * For an envelope with no target: the real average spent per budget month, offered as its target once there
     * are at least two closed budget months of history. Null when there isn't enough history.
     */
    fun suggestedTarget(e: EnvelopeEntity): Long? {
        if (e.target != null || e.mode != EnvelopeMode.FILL_TO_TARGET) return null
        val spent = spentPerClosedMonth(e.id)
        if (spent.size < 2) return null
        return spent.sum() / spent.size
    }

    /** Debt envelope: what's still owed, after the payments made from it since [EnvelopeEntity.debtOwedSince]. */
    fun debtRemaining(e: EnvelopeEntity): Long? {
        val owed = e.debtOwed ?: return null
        val since = e.debtOwedSince ?: LocalDate.MIN
        val paid = -budgetLines.filter { l ->
            l.bucket == LineBucket.ENVELOPE && l.envelopeId == e.id && l.amount < 0 && !txById[l.transactionId]!!.date.isBefore(since)
        }.sumOf { it.amount }
        return maxOf(0, owed - paid)
    }

    /** Seasonal: the 12-month average of its linked bills per payday, or null without a year of bill history. */
    fun seasonalPerPayday(e: EnvelopeEntity, today: LocalDate): Long? {
        val billIds = bills.filter { it.envelopeId == e.id }.map { it.id }.toSet()
        if (billIds.isEmpty()) return null
        val since = today.minusDays(365)
        // what the bills really cost: a rest moved to a Debt envelope isn't a payment
        val paid = billPayments.filter { it.billId in billIds && it.date.isAfter(since) && it.method != net.darkrelay.deaddropfinance.data.PaymentMethod.DEBT }
        if (paid.isEmpty() || paid.minOf { it.date }.isAfter(today.minusDays(330))) return null
        val paydaysPerYear = maxOf(1, 365 / paydayIntervalDays())
        return paid.sumOf { it.amount } / paydaysPerYear
    }

    /**
     * A Fill to target envelope with no target that bills feed: each payday adds a little toward every bill's next
     * due date, so a yearly or irregular bill's money is there when it's due. The rate is the least that gets each
     * bill's money in by its date (bills taken in due-date order, what's in the envelope counted first).
     */
    fun billFillPerPayday(e: EnvelopeEntity, balance: Long, today: LocalDate): Long {
        val mine = bills.filter { it.envelopeId == e.id && !it.deleted }
        if (mine.isEmpty()) return 0
        val next = mine.mapNotNull { Bills.openCycle(it, billPayments, today) }.filter { it.remaining > 0 }.sortedBy { it.due }
        val interval = paydayIntervalDays()
        var need = 0L
        var rate = 0L
        for (c in next) {
            need += c.remaining
            val days = ChronoUnit.DAYS.between(today, c.due)
            val paydays = if (days <= 0) 1 else maxOf(1, days / interval + if (days % interval == 0L) 0 else 1)
            rate = maxOf(rate, ceilDiv(maxOf(0, need - balance), paydays))
        }
        return rate
    }

    /** Goal: the amount to add per payday to reach the goal by its date. */
    fun goalPerPayday(e: EnvelopeEntity, today: LocalDate): Long {
        val goal = e.goalAmount ?: return 0
        val remaining = goal - envelopeBalance(e.id)
        if (remaining <= 0) return 0
        val date = e.goalDate ?: return remaining
        val days = ChronoUnit.DAYS.between(today, date)
        val paydaysLeft = if (days <= 0) 1 else days / paydayIntervalDays() + 1
        return ceilDiv(remaining, paydaysLeft)
    }

    /** Whether the envelope's screen offers "Cover from Rollover": it's negative and Ready to Assign can't cover it. */
    fun canCoverFromRollover(e: EnvelopeEntity): Long? {
        if (e.mode == EnvelopeMode.ROLLOVER) return null
        val bal = envelopeBalance(e.id)
        val roll = rollover?.let { envelopeBalance(it.id) } ?: 0
        if (bal >= 0 || readyToAssign >= -bal || roll <= 0) return null
        return minOf(-bal, roll)
    }

    companion object {
        const val UNCATEGORIZED = "Uncategorized"

        fun ceilDiv(a: Long, b: Long): Long = if (b <= 0) a else (a + b - 1) / b
    }
}
