package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.newId

/** A rule the user broke; the message is shown as is. */
class BudgetRuleException(message: String) : Exception(message)

/**
 * Everything the screens can change, each as one atomic write through BudgetStore (so each also writes its change
 * log rows). Checks the rules first and throws BudgetRuleException with a plain message when one is broken.
 */
class BudgetActions(private val store: BudgetStore, private val receiptFiles: net.darkrelay.deaddropfinance.data.ReceiptFiles? = null) {

    private val dao get() = store.dao

    suspend fun snapshot(): Budget = Budget(
        dao.accountsNow(), dao.envelopesNow(), dao.transactionsNow(), dao.linesNow(), dao.movesNow(),
        dao.billsNow(), dao.billPaymentsNow(),
    )

    // ---- accounts --------------------------------------------------------------------------------------------

    suspend fun saveAccount(
        id: String?, name: String, type: AccountType, startingBalance: Long, startDate: LocalDate,
        trackOnly: Boolean? = null, heldAmount: Long? = null,
    ): String {
        if (name.isBlank()) throw BudgetRuleException("Give the account a name.")
        if (heldAmount != null && heldAmount < 0) throw BudgetRuleException("A held amount can't be negative.")
        return store.write {
            val old = id?.let { dao.account(it) }
            val new = old?.copy(
                name = name.trim(), type = type, startingBalance = startingBalance, startDate = startDate,
                trackOnly = trackOnly ?: old.trackOnly, heldAmount = heldAmount ?: old.heldAmount, updatedAt = now,
            )
                ?: AccountEntity(
                    name = name.trim(), type = type, startingBalance = startingBalance, startDate = startDate,
                    trackOnly = trackOnly ?: false, heldAmount = heldAmount ?: 0,
                    sortOrder = dao.accountsNow().size, createdAt = now, updatedAt = now,
                )
            put(old, new).id
        }
    }

    /** Archiving takes the account's balance out of the budget (Ready to Assign); unarchiving puts it back. */
    suspend fun setAccountArchived(id: String, archived: Boolean) = store.write {
        val old = dao.account(id) ?: return@write
        put(old, old.copy(archived = archived, updatedAt = now))
    }

    // ---- envelopes -------------------------------------------------------------------------------------------

    data class EnvelopeDraft(
        val name: String,
        val mode: EnvelopeMode,
        val target: Long? = null,
        val perPayday: Long? = null,
        val goalAmount: Long? = null,
        val goalDate: LocalDate? = null,
        val debtOwed: Long? = null,
        val requiredPayment: Long? = null,
        val dueDate: LocalDate? = null,
        /** Everyday spending (counts in Safe to Spend); null keeps what the envelope has. */
        val everyday: Boolean? = null,
        /** Debt: remind like a bill, [reminderDays] ahead; null keeps what the envelope has. */
        val reminder: Boolean? = null,
        val reminderDays: Int? = null,
    )

    suspend fun saveEnvelope(id: String?, d: EnvelopeDraft, today: LocalDate = LocalDate.now()): String {
        if (d.name.isBlank()) throw BudgetRuleException("Give the envelope a name.")
        if (d.mode == EnvelopeMode.ROLLOVER || d.mode == EnvelopeMode.CASH) throw BudgetRuleException("That kind of envelope is made by the app.")
        listOf(d.target, d.perPayday, d.goalAmount, d.debtOwed, d.requiredPayment).forEach {
            if (it != null && it < 0) throw BudgetRuleException("Amounts here can't be negative.")
        }
        if (d.mode == EnvelopeMode.GOAL && (d.goalAmount == null || d.goalDate == null)) throw BudgetRuleException("A goal needs an amount and a date.")
        return store.write {
            val old = id?.let { dao.envelope(it) }
            if (old != null && old.isSystem) {
                // Rollover and Cash keep their mode; only the name can change
                return@write put(old, old.copy(name = d.name.trim(), everyday = d.everyday ?: old.everyday, updatedAt = now)).id
            }
            val owedSince = when {
                d.debtOwed == null -> null
                old?.debtOwed == d.debtOwed && old.debtOwedSince != null -> old.debtOwedSince
                else -> today
            }
            val base = old ?: EnvelopeEntity(
                name = "", mode = d.mode, sortOrder = (dao.envelopesNow().maxOfOrNull { it.sortOrder } ?: 0) + 1,
                createdAt = now, updatedAt = now,
            )
            val new = base.copy(
                name = d.name.trim(), mode = d.mode, target = d.target, perPayday = d.perPayday, goalAmount = d.goalAmount,
                goalDate = d.goalDate, debtOwed = d.debtOwed, debtOwedSince = owedSince, requiredPayment = d.requiredPayment,
                dueDate = d.dueDate, everyday = d.everyday ?: base.everyday, reminder = d.reminder ?: base.reminder,
                reminderDays = d.reminderDays ?: base.reminderDays, updatedAt = now,
            )
            put(old, new).id
        }
    }

    suspend fun setEnvelopeArchived(id: String, archived: Boolean) = store.write {
        val old = dao.envelope(id) ?: return@write
        if (old.mode == EnvelopeMode.ROLLOVER) throw BudgetRuleException("Rollover can't be archived or deleted.")
        put(old, old.copy(archived = archived, updatedAt = now))
    }

    /** The new fill order, as the full list of envelope ids top to bottom. */
    suspend fun reorderEnvelopes(orderedIds: List<String>) = store.write {
        orderedIds.forEachIndexed { i, id ->
            val old = dao.envelope(id) ?: return@forEachIndexed
            if (old.sortOrder != i) put(old, old.copy(sortOrder = i, updatedAt = now))
        }
    }

    // ---- transactions ----------------------------------------------------------------------------------------

    /**
     * Where part of a transaction goes. A null envelope with [toReadyToAssign] and [transfer] false means To sort.
     * [transfer]: money between the user's own accounts, neither spending nor income.
     */
    data class LineDraft(val envelopeId: String?, val amount: Long, val toReadyToAssign: Boolean = false, val transfer: Boolean = false)

    data class TransactionDraft(
        val accountId: String,
        val date: LocalDate,
        val payee: String,
        val memo: String,
        val amount: Long,
        val lines: List<LineDraft>,
        val pending: Boolean = false,
    )

    /** Saves a transaction from the edit screen. Changing where its money goes counts as sorting it by hand. */
    suspend fun saveTransaction(id: String?, d: TransactionDraft): String {
        checkLines(d)
        return store.write {
            val old = id?.let { dao.transaction(it) }
            if (old != null && old.kind != TransactionKind.NORMAL) throw BudgetRuleException("That's a transfer.")
            val linesChanged = old == null || dao.linesOf(old.id).map { Triple(it.bucket, it.envelopeId, it.amount) } != d.lines.map { Triple(bucketOf(it), it.envelopeId, it.amount) }
            val sorted = d.lines.any { bucketOf(it) != LineBucket.UNSORTED }
            var tx = (old ?: TransactionEntity(accountId = d.accountId, date = d.date, amount = d.amount, createdAt = now, updatedAt = now))
                .copy(accountId = d.accountId, date = d.date, payee = d.payee.trim(), memo = d.memo.trim(), amount = d.amount, pending = d.pending, updatedAt = now)
            if (linesChanged) {
                tx = tx.copy(
                    sortedBy = if (sorted) SortedBy.HAND else null, sortedAt = if (sorted) now else null, ruleId = null,
                    returnOf = tx.returnOf.takeIf { sorted && d.amount > 0 },
                )
            }
            put(old, tx)
            replaceLines(this, tx.id, d.lines)
            tx.id
        }
    }

    /**
     * Sets where an existing transaction's money goes, and how that was decided (by hand, an approved suggestion, a
     * rule as it arrived, or a confirmed return). The lines must add up to the amount, as always.
     */
    suspend fun setLines(txId: String, lines: List<LineDraft>, sortedBy: SortedBy, ruleId: String? = null, returnOf: String? = null) {
        val tx = dao.transaction(txId) ?: throw BudgetRuleException("That transaction is gone.")
        if (tx.kind != TransactionKind.NORMAL) throw BudgetRuleException("That's a transfer.")
        checkLines(TransactionDraft(tx.accountId, tx.date, tx.payee, tx.memo, tx.amount, lines, tx.pending))
        store.write {
            val old = dao.transaction(txId)!!
            put(old, old.copy(sortedBy = sortedBy, sortedAt = now, ruleId = ruleId, returnOf = returnOf, updatedAt = now))
            replaceLines(this, txId, lines)
        }
    }

    /** Puts a sorted transaction back in To sort (a return undone, say). */
    suspend fun unsort(txId: String) {
        val tx = dao.transaction(txId) ?: return
        if (tx.kind != TransactionKind.NORMAL) return
        store.write {
            // items it returned can be returned again
            dao.itemReturnsNow().filter { it.transactionId == txId }.forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
            put(tx, tx.copy(sortedBy = null, sortedAt = null, ruleId = null, returnOf = null, updatedAt = now))
            replaceLines(this, txId, listOf(LineDraft(null, tx.amount)))
        }
    }

    /**
     * The balance check's fix, only ever when the user confirms it: a transaction that brings the account to the
     * bank's balance. Its money comes out of (or goes into) Ready to Assign, since it was never in an envelope.
     */
    suspend fun addAdjustment(accountId: String, amount: Long, date: LocalDate): String {
        if (amount == 0L) throw BudgetRuleException("Nothing to adjust.")
        val a = dao.account(accountId) ?: throw BudgetRuleException("That account is gone.")
        val day = maxOf(date, a.startDate)
        return store.write {
            val tx = put(
                null,
                TransactionEntity(
                    accountId = accountId, date = day, payee = "Balance adjustment", memo = "Added to match the bank's balance", amount = amount,
                    sortedBy = SortedBy.HAND, sortedAt = now, createdAt = now, updatedAt = now,
                ),
            )
            put(null, TransactionLineEntity(transactionId = tx.id, bucket = LineBucket.READY_TO_ASSIGN, amount = amount, createdAt = now, updatedAt = now))
            tx.id
        }
    }

    private fun bucketOf(ld: LineDraft) = when {
        ld.envelopeId != null -> LineBucket.ENVELOPE
        ld.transfer -> LineBucket.TRANSFER
        ld.toReadyToAssign -> LineBucket.READY_TO_ASSIGN
        else -> LineBucket.UNSORTED
    }

    private suspend fun replaceLines(w: BudgetStore.Writer, txId: String, drafts: List<LineDraft>) {
        val existing = dao.linesOf(txId)
        drafts.forEachIndexed { i, ld ->
            val bucket = bucketOf(ld)
            val old = existing.getOrNull(i)
            val new = (old ?: TransactionLineEntity(transactionId = txId, bucket = bucket, amount = ld.amount, createdAt = w.now, updatedAt = w.now))
                .copy(bucket = bucket, envelopeId = ld.envelopeId, amount = ld.amount, sortOrder = i, updatedAt = w.now)
            w.put(old, new)
        }
        existing.drop(drafts.size).forEach { w.put(it, it.copy(deleted = true, updatedAt = w.now)) }
    }

    /** How many transactions deleting this account takes with it (its own, plus transfers to or from it). */
    suspend fun deleteAccountCount(id: String): Int = dao.transactionsNow().count { it.accountId == id || it.transferAccountId == id }

    /**
     * Deletes an account for good: the account and its transactions become tombstones (the change log carries them,
     * as every delete), so envelope balances and Ready to Assign drop what they brought. A transfer to or from
     * another account keeps that account's side: it becomes an ordinary entry there, in To sort. Any bank link for
     * the account is removed too.
     */
    suspend fun deleteAccount(id: String) {
        val goneReceipts = mutableListOf<String>()
        store.write {
            val a = dao.account(id) ?: return@write
            val all = dao.transactionsNow()
            // receipts on the account's own transactions go with them, photos included
            val own = all.filter { it.accountId == id && it.kind == TransactionKind.NORMAL }.map { it.id }.toSet()
            dao.receiptsNow().filter { it.transactionId in own }.forEach { r -> Receipts.deleteRows(this, dao, r.id); goneReceipts += r.id }
            dao.itemReturnsNow().filter { it.transactionId in own }.forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
            for (t in all) {
                val touches = t.accountId == id || t.transferAccountId == id
                if (!touches) continue
                val other = when {
                    t.kind != TransactionKind.TRANSFER -> null
                    t.accountId == id -> t.transferAccountId
                    else -> t.accountId
                }
                if (other == null || other == id) {
                    put(t, t.copy(deleted = true, updatedAt = now))
                    dao.linesOf(t.id).forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
                    (dao.movesOfPayday(t.id) + dao.movesLinkedTo(t.id)).forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
                    t.washGroupId?.let { g ->
                        all.filter { it.washGroupId == g && it.id != t.id && !(it.accountId == id) }.forEach { put(it, it.copy(washGroupId = null, updatedAt = now)) }
                    }
                } else {
                    // the other account's side stays: money in if the deleted account sent it, money out if it received it
                    val signed = if (t.accountId == id) t.amount else -t.amount
                    val otherAccount = dao.account(other)
                    dao.movesLinkedTo(t.id).forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
                    put(
                        t,
                        t.copy(
                            accountId = other, kind = TransactionKind.NORMAL, transferAccountId = null, amount = signed,
                            payee = if (signed > 0) "Transfer from ${a.name}" else "Transfer to ${a.name}", updatedAt = now,
                        ),
                    )
                    put(
                        null,
                        TransactionLineEntity(
                            transactionId = t.id, bucket = if (otherAccount?.onBudget == false) LineBucket.OFF_BUDGET else LineBucket.UNSORTED,
                            amount = signed, createdAt = now, updatedAt = now,
                        ),
                    )
                }
            }
            put(a, a.copy(deleted = true, updatedAt = now))
        }
        goneReceipts.forEach { receiptFiles?.delete(it) }
        // this phone's bank link for it (not change-logged)
        store.sync.linkedNow().filter { it.accountId == id }.forEach {
            store.sync.upsertLinked(it.copy(accountId = null, historyFrom = null, lastSyncedDate = null, setStartBalance = false))
        }
    }

    /**
     * Soft delete: the transaction, its lines, and moves made for it (payday, ATM) become tombstones. Its receipt goes
     * back to the unmatched list, or is deleted too with [deleteReceipt]. Items it returned can be returned again.
     */
    suspend fun deleteTransaction(id: String, deleteReceipt: Boolean = false) {
        val gone = mutableListOf<String>()
        store.write {
            dao.receiptsNow().filter { it.transactionId == id }.forEach { r ->
                if (deleteReceipt) { Receipts.deleteRows(this, dao, r.id); gone += r.id } else put(r, r.copy(transactionId = null, updatedAt = now))
            }
            dao.itemReturnsNow().filter { it.transactionId == id }.forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
            deleteTransactionRows(this, id)
        }
        gone.forEach { receiptFiles?.delete(it) }
    }

    private suspend fun deleteTransactionRows(w: BudgetStore.Writer, id: String) = with(w) {
        val old = dao.transaction(id) ?: return@with
        put(old, old.copy(deleted = true, updatedAt = now))
        dao.linesOf(id).forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
        (dao.movesOfPayday(id) + dao.movesLinkedTo(id)).forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
        old.washGroupId?.let { g ->
            dao.transactionsNow().filter { it.washGroupId == g && it.id != id }.forEach { put(it, it.copy(washGroupId = null, updatedAt = now)) }
        }
    }

    /** Sorts a To sort transaction into one envelope (or Ready to Assign, for income, or as a transfer), by hand. */
    suspend fun sortInto(txId: String, envelopeId: String?, toReadyToAssign: Boolean = false, transfer: Boolean = false) {
        val tx = dao.transaction(txId) ?: return
        setLines(txId, listOf(LineDraft(envelopeId, tx.amount, toReadyToAssign, transfer)), SortedBy.HAND)
    }

    // ---- transfers and ATM withdrawals ----------------------------------------------------------------------

    /**
     * Money between the user's own accounts: never spending or income, no envelope touched. When it goes into a
     * cash account and [fromEnvelopeId] is set, it's an ATM withdrawal: that envelope's money moves into the Cash
     * envelope (made on first use).
     */
    suspend fun saveTransfer(id: String?, fromAccountId: String, toAccountId: String, amount: Long, date: LocalDate, memo: String, fromEnvelopeId: String? = null): String {
        if (amount <= 0) throw BudgetRuleException("Enter an amount.")
        if (fromAccountId == toAccountId) throw BudgetRuleException("Choose two different accounts.")
        return store.write {
            val old = id?.let { dao.transaction(it) }
            val tx = (old ?: TransactionEntity(accountId = fromAccountId, kind = TransactionKind.TRANSFER, date = date, amount = amount, createdAt = now, updatedAt = now))
                .copy(accountId = fromAccountId, transferAccountId = toAccountId, kind = TransactionKind.TRANSFER, date = date, amount = amount, memo = memo.trim(), updatedAt = now)
            put(old, tx)
            dao.movesLinkedTo(tx.id).forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
            val toAccount = dao.account(toAccountId)
            if (fromEnvelopeId != null && toAccount?.type == AccountType.CASH) {
                val cash = dao.envelopesNow().firstOrNull { it.mode == EnvelopeMode.CASH }
                    ?: put(null, EnvelopeEntity(name = "Cash", mode = EnvelopeMode.CASH, sortOrder = (dao.envelopesNow().maxOfOrNull { it.sortOrder } ?: 0) + 1, createdAt = now, updatedAt = now))
                put(null, MoveEntity(fromEnvelopeId = fromEnvelopeId, toEnvelopeId = cash.id, amount = amount, date = date, kind = MoveKind.ATM, linkedTransactionId = tx.id, createdAt = now, updatedAt = now))
            }
            tx.id
        }
    }

    // ---- moving money ----------------------------------------------------------------------------------------

    /** From Ready to Assign (null) or an envelope, to an envelope or back to Ready to Assign (null). */
    suspend fun move(fromEnvelopeId: String?, toEnvelopeId: String?, amount: Long, kind: MoveKind = MoveKind.MOVE, date: LocalDate = LocalDate.now()) {
        if (amount <= 0) throw BudgetRuleException("Enter an amount.")
        if (fromEnvelopeId == toEnvelopeId) throw BudgetRuleException("Choose two different places.")
        store.write {
            put(null, MoveEntity(fromEnvelopeId = fromEnvelopeId, toEnvelopeId = toEnvelopeId, amount = amount, date = date, kind = kind, createdAt = now, updatedAt = now))
        }
    }

    /** Assign from Ready to Assign, at most what's there. */
    suspend fun assign(envelopeId: String, amount: Long) {
        val rta = snapshot().readyToAssign
        if (amount > rta) throw BudgetRuleException("Only ${Money.format(maxOf(0, rta))} is ready to assign.")
        move(null, envelopeId, amount, MoveKind.ASSIGN)
    }

    /** The Assign screen's Add: fills an envelope up to what its mode wants this payday, or as much as there is. */
    suspend fun addToEnvelope(envelopeId: String, today: LocalDate = LocalDate.now()): Long {
        val b = snapshot()
        val e = b.envelopes.first { it.id == envelopeId }
        val want = Payday.amountFor(e, b.envelopeBalance(e.id), b, today)
        val give = minOf(want, b.readyToAssign)
        if (give > 0) move(null, envelopeId, give, MoveKind.ASSIGN)
        return give
    }

    suspend fun coverFromRollover(envelopeId: String): Long {
        val b = snapshot()
        val e = b.envelopes.first { it.id == envelopeId }
        val amount = b.canCoverFromRollover(e) ?: throw BudgetRuleException("Nothing to cover here.")
        move(b.rollover!!.id, envelopeId, amount, MoveKind.COVER)
        return amount
    }

    // ---- payday ----------------------------------------------------------------------------------------------

    /**
     * The user marks a deposit as their payday (FEATURES.md: payday is the deposit arriving, never a calendar
     * date; detection comes later). Its money goes to Ready to Assign, the budget month closes (Rollover sweep),
     * and auto-fill runs in fill order. Returns the plan, shortfalls included, for the screen.
     */
    suspend fun markPayday(txId: String, today: LocalDate = LocalDate.now()): Payday.Plan {
        val tx = dao.transaction(txId) ?: throw BudgetRuleException("That transaction is gone.")
        if (tx.kind != TransactionKind.NORMAL || tx.amount <= 0 || tx.isWash) throw BudgetRuleException("Only a deposit can be a payday.")
        if (tx.isPayday) throw BudgetRuleException("That's already marked as payday.")
        if (tx.returnOf != null) throw BudgetRuleException("A return isn't income, so it can't be a payday.")
        return store.write {
            put(tx, tx.copy(isPayday = true, sortedBy = SortedBy.HAND, sortedAt = now, ruleId = null, updatedAt = now))
            // the deposit itself lands in Ready to Assign
            replaceLines(this, tx.id, listOf(LineDraft(null, tx.amount, toReadyToAssign = true)))
            val b = snapshot()
            val closes = b.paydayDates.any { it.isBefore(tx.date) } || b.transactions.count { it.isPayday } > 1
            val plan = Payday.plan(b, today, closes)
            for (m in plan.sweeps) {
                put(null, MoveEntity(fromEnvelopeId = m.fromEnvelopeId, toEnvelopeId = m.toEnvelopeId, amount = m.amount, date = tx.date, kind = MoveKind.SWEEP, paydayTransactionId = tx.id, createdAt = now, updatedAt = now))
            }
            for (m in plan.fills) {
                put(null, MoveEntity(fromEnvelopeId = null, toEnvelopeId = m.toEnvelopeId, amount = m.amount, date = tx.date, kind = MoveKind.AUTOFILL, paydayTransactionId = tx.id, createdAt = now, updatedAt = now))
            }
            plan
        }
    }

    /** Undoes a payday: its sweep and auto-fill moves become tombstones; the deposit stays in Ready to Assign. */
    suspend fun unmarkPayday(txId: String) = store.write {
        val tx = dao.transaction(txId) ?: return@write
        if (!tx.isPayday) return@write
        put(tx, tx.copy(isPayday = false, updatedAt = now))
        dao.movesOfPayday(txId).forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
    }

    // ---- CSV import ------------------------------------------------------------------------------------------

    data class ImportResult(val imported: Int, val posted: Int, val skipped: Int, val bad: Int, val skippedByUser: Int = 0, val newIds: List<String> = emptyList())

    /**
     * Writes a planned import in one go: new rows land in To sort (uncategorized), ATM fees and rebates tagged;
     * a row matching a pending transaction posts that one instead of adding a second.
     */
    suspend fun importCsv(accountId: String, plan: CsvImport.Plan): ImportResult = store.write {
        // an account outside the budget records imported rows as handled instead of putting them in To sort
        val bucket = if (dao.account(accountId)?.onBudget == false) LineBucket.OFF_BUDGET else LineBucket.UNSORTED
        val newIds = mutableListOf<String>()
        for (r in plan.toImport) {
            val tx = put(
                null,
                TransactionEntity(
                    accountId = accountId, date = r.date, payee = r.description, memo = r.memo, amount = r.amount,
                    fee = CsvImport.isAtmFee(r.description, r.amount), feeRebate = CsvImport.isFeeRebate(r.description, r.amount),
                    source = net.darkrelay.deaddropfinance.data.TransactionSource.CSV, importDescription = r.description,
                    createdAt = now, updatedAt = now,
                ),
            )
            put(null, TransactionLineEntity(transactionId = tx.id, bucket = bucket, amount = r.amount, createdAt = now, updatedAt = now))
            newIds += tx.id
        }
        for ((r, p) in plan.matchedPending) {
            val old = dao.transaction(p.id) ?: continue
            put(old, old.copy(pending = false, date = r.date, importDescription = r.description, updatedAt = now))
        }
        ImportResult(plan.toImport.size, plan.matchedPending.size, plan.alreadyHere.size, plan.bad.size, plan.skippedByUser.size, newIds)
    }

    // ---- pass-through (wash) ---------------------------------------------------------------------------------

    /** Pairs an incoming and an outgoing transaction of the same amount as a wash: neither income nor spending. */
    suspend fun markWash(aId: String, bId: String) = store.write {
        val a = dao.transaction(aId) ?: throw BudgetRuleException("That transaction is gone.")
        val b = dao.transaction(bId) ?: throw BudgetRuleException("That transaction is gone.")
        if (a.kind != TransactionKind.NORMAL || b.kind != TransactionKind.NORMAL || a.amount + b.amount != 0L || a.amount == 0L) {
            throw BudgetRuleException("A wash pairs money in and money out of the same amount.")
        }
        if (a.isPayday || b.isPayday) throw BudgetRuleException("Unmark the payday first.")
        val g = newId()
        put(a, a.copy(washGroupId = g, updatedAt = now))
        put(b, b.copy(washGroupId = g, updatedAt = now))
    }

    suspend fun unmarkWash(txId: String) = store.write {
        val t = dao.transaction(txId) ?: return@write
        val g = t.washGroupId ?: return@write
        dao.transactionsNow().filter { it.washGroupId == g }.forEach { put(it, it.copy(washGroupId = null, updatedAt = now)) }
    }

    /** Transactions that could be the other half of a wash for [txId]: opposite amount, within three days. */
    suspend fun washCandidates(txId: String): List<TransactionEntity> {
        val t = dao.transaction(txId) ?: return emptyList()
        return dao.transactionsNow().filter {
            it.id != t.id && it.kind == TransactionKind.NORMAL && !it.isWash && !it.isPayday && it.amount == -t.amount &&
                kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it.date, t.date)) <= 3
        }
    }

    companion object {
        /** Checks a normal transaction's lines: they must add up to the amount, and carry its sign. */
        fun checkLines(d: TransactionDraft) {
            if (d.amount == 0L) throw BudgetRuleException("Enter an amount.")
            if (d.lines.isEmpty()) throw BudgetRuleException("Choose where the money goes.")
            val sum = d.lines.sumOf { it.amount }
            if (sum != d.amount) {
                throw BudgetRuleException("The split adds up to ${Money.format(sum)}, not ${Money.format(d.amount)}.")
            }
            if (d.lines.any { it.amount == 0L || (it.amount > 0) != (d.amount > 0) }) {
                throw BudgetRuleException("Every split line needs an amount with the same sign as the transaction.")
            }
            if (d.amount < 0 && d.lines.any { it.toReadyToAssign && !it.transfer && it.envelopeId == null }) {
                throw BudgetRuleException("Spending can't go to Ready to Assign. Choose an envelope.")
            }
        }
    }
}
