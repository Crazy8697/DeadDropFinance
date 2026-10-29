package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.BudgetDao
import net.darkrelay.deaddropfinance.data.BudgetStore

/**
 * What goes with a transaction that's deleted, whichever way: by the user, with its account, or because the bank
 * stopped listing it (a pending row that vanished, a disconnect that removes its rows). Nothing may be left counting
 * it: not a payday's sweep and fill, not a bill payment, not a parked buy's match.
 */
object TransactionCleanup {

    /**
     * Tombstones the transaction and its lines, the moves made for it (payday sweep and fill, ATM), and the bill
     * payments it made; frees its wash partner and puts a parked buy matched to it back to waiting for its charge.
     * Receipts and item returns are the caller's (deleting a transaction by hand can delete its receipt too).
     */
    suspend fun tombstone(w: BudgetStore.Writer, dao: BudgetDao, id: String) = with(w) {
        val old = dao.transaction(id) ?: return@with
        if (!old.deleted) put(old, old.copy(deleted = true, updatedAt = now))
        dao.linesOf(id).forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
        (dao.movesOfPayday(id) + dao.movesLinkedTo(id)).forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
        old.washGroupId?.let { g ->
            dao.transactionsNow().filter { it.washGroupId == g && it.id != id }.forEach { put(it, it.copy(washGroupId = null, updatedAt = now)) }
        }
        dao.billPaymentsNow().filter { it.transactionId == id }.forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
        dao.parkedBuysNow().filter { it.transactionId == id }.forEach { put(it, it.copy(transactionId = null, updatedAt = now)) }
    }

    /** A transaction gone without the user deciding: its receipt waits for a charge again, and what it returned can be returned again. */
    suspend fun releaseReceipts(w: BudgetStore.Writer, dao: BudgetDao, id: String) = with(w) {
        dao.receiptsNow().filter { it.transactionId == id }.forEach { put(it, it.copy(transactionId = null, updatedAt = now)) }
        dao.itemReturnsNow().filter { it.transactionId == id }.forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
    }

    /** The bank changed a transaction's amount (a tip, a final fuel amount): the bill payments it made follow. */
    suspend fun followAmount(w: BudgetStore.Writer, dao: BudgetDao, id: String, amount: Long) = with(w) {
        dao.billPaymentsNow().filter { it.transactionId == id && it.amount != -amount }.forEach { put(it, it.copy(amount = -amount, updatedAt = now)) }
    }
}
