package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ItemReturnEntity
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptFiles
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.data.ReceiptKind
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionKind

/**
 * Receipts: saving what was scanned (after the user checked it), matching it to its transaction, split by items, and
 * returning items. Every write goes through BudgetStore with its change log rows. Photos are deleted with their
 * receipt through [files].
 */
class Receipts(
    private val store: BudgetStore,
    private val actions: BudgetActions = BudgetActions(store),
    private val files: ReceiptFiles? = null,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val dao get() = store.dao

    data class DraftItem(val text: String, val quantityMilli: Long = 1000, val unitPrice: Long? = null, val amount: Long, val id: String? = null)

    data class Draft(
        val store: String,
        val date: LocalDate?,
        val total: Long?,
        val tax: Long?,
        val items: List<DraftItem>,
        val kind: ReceiptKind = ReceiptKind.PURCHASE,
        val lowConfidence: Set<String> = emptySet(),
        val rawText: String = "",
        val pages: Int = 1,
    )

    /** Saves a receipt as the user left it on the form. [id] is the one its photos were stored under. */
    suspend fun save(id: String, d: Draft, transactionId: String? = null): String {
        if (d.total != null && d.total < 0 || d.tax != null && d.tax < 0) throw BudgetRuleException("Amounts here can't be negative.")
        if (d.items.any { it.text.isBlank() }) throw BudgetRuleException("Every item needs a description, or remove it.")
        if (d.items.any { it.quantityMilli <= 0 }) throw BudgetRuleException("A quantity has to be more than zero.")
        return store.write {
            val old = dao.receipt(id)
            val base = old ?: ReceiptEntity(id = id, transactionId = transactionId, createdAt = now, updatedAt = now)
            put(
                old,
                base.copy(
                    transactionId = old?.transactionId ?: transactionId, store = d.store.trim(), date = d.date, total = d.total, tax = d.tax,
                    kind = d.kind, pages = d.pages, lowConfidence = d.lowConfidence.sorted().joinToString(","), rawText = d.rawText, updatedAt = now,
                ),
            )
            val existing = dao.itemsOf(id).associateBy { it.id }
            val kept = mutableSetOf<String>()
            d.items.forEachIndexed { i, di ->
                val o = di.id?.let { existing[it] }
                val n = (o ?: ReceiptItemEntity(receiptId = id, amount = di.amount, createdAt = now, updatedAt = now)).copy(
                    line = i, text = di.text.trim(), name = ReceiptParser.cleanName(di.text), quantityMilli = di.quantityMilli,
                    unitPrice = di.unitPrice, amount = di.amount, updatedAt = now,
                )
                put(o, n)
                kept += n.id
            }
            existing.values.filter { it.id !in kept }.forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
            id
        }
    }

    suspend fun receiptOf(txId: String): ReceiptEntity? = dao.receiptsNow().firstOrNull { it.transactionId == txId }

    /** Puts a receipt on a transaction. A transaction has one receipt at a time. */
    suspend fun attach(receiptId: String, txId: String) = store.write {
        val r = dao.receipt(receiptId) ?: throw BudgetRuleException("That receipt is gone.")
        val t = dao.transaction(txId) ?: throw BudgetRuleException("That transaction is gone.")
        if (t.kind != TransactionKind.NORMAL) throw BudgetRuleException("A transfer can't have a receipt.")
        if (dao.receiptsNow().any { it.transactionId == txId && it.id != receiptId }) throw BudgetRuleException("That transaction already has a receipt.")
        put(r, r.copy(transactionId = txId, updatedAt = now))
    }

    /** Takes a receipt off its transaction: it goes back to the unmatched list. */
    suspend fun detach(receiptId: String) = store.write {
        val r = dao.receipt(receiptId) ?: return@write
        if (r.transactionId != null) put(r, r.copy(transactionId = null, updatedAt = now))
    }

    /** Deletes a receipt for good: its row, items and their returns (tombstones), and its photos. */
    suspend fun delete(receiptId: String) {
        store.write { deleteRows(this, dao, receiptId) }
        files?.delete(receiptId)
    }

    // ---- matching -------------------------------------------------------------------------------------------------

    private suspend fun taken() = dao.receiptsNow().mapNotNull { it.transactionId }.toSet()

    suspend fun candidates(receiptId: String): List<ReceiptMatch.Candidate> {
        val r = dao.receipt(receiptId) ?: return emptyList()
        return ReceiptMatch.candidates(actions.snapshot(), r, dao.itemsOf(receiptId), taken(), today())
    }

    /** Matches one receipt: one clear match is attached; otherwise what to ask, or None (it waits). */
    suspend fun match(receiptId: String): ReceiptMatch.Decision {
        val d = ReceiptMatch.decide(candidates(receiptId))
        if (d is ReceiptMatch.Decision.One) attach(receiptId, d.c.tx.id)
        return d
    }

    /** After a sync or an import: waiting receipts whose transaction came in are attached, when the match is clear. */
    suspend fun matchWaiting(): Int {
        var n = 0
        for (r in dao.receiptsNow().filter { it.transactionId == null }) {
            if (match(r.id) is ReceiptMatch.Decision.One) n++
        }
        return n
    }

    // ---- split by items -------------------------------------------------------------------------------------------

    /** Splits the receipt's transaction by its items: [pick] is item id to envelope id. The photos stay. */
    suspend fun splitByItems(receiptId: String, pick: Map<String, String?>) {
        val r = dao.receipt(receiptId) ?: throw BudgetRuleException("That receipt is gone.")
        val txId = r.transactionId ?: throw BudgetRuleException("Match the receipt to its transaction first.")
        val tx = dao.transaction(txId) ?: throw BudgetRuleException("That transaction is gone.")
        val items = dao.itemsOf(receiptId)
        val had = dao.linesOf(txId).filter { it.categoryId != null && it.envelopeId != null }.associate { it.envelopeId to it.categoryId }
        val lines = ItemSplit.lines(tx.amount, items, pick).map { l -> l.copy(categoryId = had[l.envelopeId]) }
        actions.setLines(txId, lines, SortedBy.HAND, requireCategory = false)
        store.write {
            for (i in items) {
                val e = pick[i.id]
                if (e != i.envelopeId) put(i, i.copy(envelopeId = e, updatedAt = now))
            }
        }
    }

    // ---- returning items ------------------------------------------------------------------------------------------

    /**
     * A return of some of a purchase's items: [picks] is item id to quantity (thousandths). The refund [creditId]
     * goes back to each item's envelope; the items can't be returned again past what was bought.
     */
    suspend fun returnItems(creditId: String, purchaseId: String, picks: Map<String, Long>) {
        val b = actions.snapshot()
        val credit = b.transactions.firstOrNull { it.id == creditId } ?: throw BudgetRuleException("That transaction is gone.")
        if (credit.amount <= 0) throw BudgetRuleException("Only money coming in can be a return.")
        if (credit.isPayday) throw BudgetRuleException("Unmark the payday first.")
        val receipt = receiptOf(purchaseId) ?: throw BudgetRuleException("That purchase has no receipt.")
        val items = dao.itemsOf(receipt.id)
        val returns = dao.itemReturnsNow()
        val chosen = LinkedHashMap<ReceiptItemEntity, Long>()
        for ((id, q) in picks) {
            if (q <= 0) continue
            val item = items.firstOrNull { it.id == id } ?: continue
            val left = ItemReturns.remainingMilli(item, returns, except = creditId)
            if (q > left) throw BudgetRuleException("Only ${qty(left)} of ${item.text} is left to return.")
            chosen[item] = q
        }
        val tax = receipt.tax ?: 0
        val selected = chosen.entries.sumOf { (i, q) -> ItemReturns.value(i, q, items, tax) }
        if (!ItemReturns.closeEnough(credit.amount, selected)) {
            throw BudgetRuleException("The items come to ${Money.format(selected)}; the refund is ${Money.format(credit.amount)}. Pick the items that match it.")
        }
        val (lines, perItem) = ItemReturns.lines(credit.amount, chosen, items, tax, ItemReturns.purchaseLines(b, purchaseId))
        // the refund takes its amount back off the category the purchase's money was in
        val catOf = b.lines.filter { it.transactionId == purchaseId && it.categoryId != null && it.envelopeId != null }.associate { it.envelopeId to it.categoryId }
        actions.setLines(creditId, lines.map { it.copy(categoryId = catOf[it.envelopeId]) }, SortedBy.RETURN, returnOf = purchaseId)
        store.write {
            returns.filter { it.transactionId == creditId }.forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
            for ((item, q) in chosen) {
                put(null, ItemReturnEntity(itemId = item.id, transactionId = creditId, quantityMilli = q, amount = perItem[item.id] ?: 0, createdAt = now, updatedAt = now))
            }
        }
    }

    /** With a scanned return receipt on the credit: which purchase items (and how many) it names. */
    suspend fun preselect(creditId: String, purchaseId: String): Map<String, Long> {
        val back = receiptOf(creditId)?.takeIf { it.kind == ReceiptKind.RETURN } ?: return emptyMap()
        val bought = receiptOf(purchaseId) ?: return emptyMap()
        return ItemReturns.matchReturnReceipt(dao.itemsOf(back.id), dao.itemsOf(bought.id), dao.itemReturnsNow().filter { it.transactionId != creditId })
    }

    companion object {
        /** Tombstones a receipt, its items and their returns, inside a write. */
        suspend fun deleteRows(w: BudgetStore.Writer, dao: net.darkrelay.deaddropfinance.data.BudgetDao, receiptId: String) {
            val r = dao.receipt(receiptId) ?: return
            val items = dao.itemsOf(receiptId)
            val ids = items.map { it.id }.toSet()
            dao.itemReturnsNow().filter { it.itemId in ids }.forEach { w.put(it, it.copy(deleted = true, updatedAt = w.now)) }
            items.forEach { w.put(it, it.copy(deleted = true, updatedAt = w.now)) }
            if (!r.deleted) w.put(r, r.copy(deleted = true, updatedAt = w.now))
        }

        fun qty(milli: Long): String = if (milli % 1000 == 0L) (milli / 1000).toString() else "%d.%03d".format(milli / 1000, milli % 1000).trimEnd('0')
    }
}
