package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import net.darkrelay.deaddropfinance.data.ItemReturnEntity
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.data.ReceiptKind
import net.darkrelay.deaddropfinance.data.RuleAction
import net.darkrelay.deaddropfinance.data.RuleEntity
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft

/**
 * Matching a receipt to its bank transaction. By total (exact, or a bit higher for a tip or a fuel hold), by date
 * (the bank can post a few days later), and by store name. Pure.
 */
object ReceiptMatch {

    enum class Fit { EXACT, TIP }

    data class Candidate(val tx: TransactionEntity, val fit: Fit, val storeMatch: Boolean, val days: Long) {
        val score get() = (if (fit == Fit.EXACT) 100 else 60) + (if (storeMatch) 30 else 0) - days.toInt() * 2
    }

    sealed interface Decision {
        data class One(val c: Candidate) : Decision
        data class Several(val list: List<Candidate>) : Decision
        data object None : Decision
    }

    /** Days the bank may post after the receipt's date, and before it (a pending hold, a date off by one). */
    const val DAYS_AFTER = 5L
    const val DAYS_BEFORE = 1L
    /** A tip or a fuel hold: the charge may be up to this much more than the receipt (percent). */
    const val TIP_PERCENT = 30L

    private val tipWords = Regex(
        """\b(restaurant|grill|cafe|coffee|pizza|pizzeria|diner|bar|pub|tavern|bistro|kitchen|burger|burgers|taco|tacos|sushi|eatery|""" +
            """steakhouse|bbq|brewery|tip|gratuity|fuel|gas|petrol|gasoline|gallon|gallons|gal|pump|station)\b""",
    )

    /** Restaurants and gas stations: the charge can be higher than the receipt (a tip; a pump's final amount). */
    fun tipFriendly(r: ReceiptEntity, items: List<ReceiptItemEntity>): Boolean =
        tipWords.containsMatchIn((listOf(r.store, r.rawText) + items.map { it.text }).joinToString(" ").lowercase())

    /** The same store: one cleaned name starts with the other, or the store's first word is in the bank's description. */
    fun storeMatches(store: String, t: TransactionEntity): Boolean {
        val clean = Payees.clean(store)
        val first = clean.split(' ').firstOrNull { it.length >= 3 } ?: return false
        return Payees.forms(t).any { f -> Payees.sameMerchant(clean, f) || first in f.split(' ') }
    }

    /** Transactions this receipt could belong to, best first. [taken]: transactions that already have a receipt. */
    fun candidates(b: Budget, r: ReceiptEntity, items: List<ReceiptItemEntity>, taken: Set<String>, today: LocalDate): List<Candidate> {
        val total = r.total ?: return emptyList()
        if (total <= 0) return emptyList()
        val day = r.date ?: today
        val sign = if (r.kind == ReceiptKind.RETURN) 1 else -1
        val tips = r.kind == ReceiptKind.PURCHASE && tipFriendly(r, items)
        return b.transactions.asSequence()
            .filter { it.kind == TransactionKind.NORMAL && !it.isWash && it.id !in taken && it.amount * sign > 0 }
            .filter { b.accounts.firstOrNull { a -> a.id == it.accountId }?.archived != true }
            .mapNotNull { t ->
                val diff = ChronoUnit.DAYS.between(day, t.date)
                if (diff < -DAYS_BEFORE || diff > DAYS_AFTER) return@mapNotNull null
                val amt = abs(t.amount)
                val fit = when {
                    amt == total -> Fit.EXACT
                    tips && amt > total && (amt - total) * 100 <= total * TIP_PERCENT -> Fit.TIP
                    else -> return@mapNotNull null
                }
                Candidate(t, fit, storeMatches(r.store, t), abs(diff))
            }
            .sortedByDescending { it.score }
            .toList()
    }

    /**
     * One clear match is attached by itself: the only candidate, or the only exact one at a store that matches.
     * Anything else asks (Several) or waits (None).
     */
    fun decide(list: List<Candidate>): Decision {
        if (list.isEmpty()) return Decision.None
        // the only one, if it's the exact amount or at the same store (a higher charge elsewhere is only a guess)
        if (list.size == 1 && (list[0].fit == Fit.EXACT || list[0].storeMatch)) return Decision.One(list[0])
        val clear = list.filter { it.fit == Fit.EXACT && it.storeMatch }
        if (clear.size == 1) return Decision.One(clear[0])
        return Decision.Several(list)
    }
}

/** Split by items: each item to an envelope, tax (and any tip) spread in proportion, the transaction split to match. */
object ItemSplit {

    /**
     * An envelope to start an item with: a rule whose words are in the item's name, else the envelope the item had,
     * else the one the transaction is in (when it's in one). A discount goes with the item above it.
     */
    fun suggestions(items: List<ReceiptItemEntity>, rules: List<RuleEntity>, liveEnvelopes: Set<String>, txEnvelope: String?): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        var previous: String? = null
        for (it in items.sortedBy { i -> i.line }) {
            val fromRule = rules.sortedBy { r -> r.priority }.firstOrNull { r ->
                r.action == RuleAction.ENVELOPE && !r.deleted && Payees.contains(it.name, r.matchText)
            }?.let { r -> Rules.splits(r).singleOrNull()?.target?.takeIf { t -> t in liveEnvelopes } }
            val e = when {
                it.envelopeId != null && it.envelopeId in liveEnvelopes -> it.envelopeId
                it.amount < 0 && previous != null -> previous
                fromRule != null -> fromRule
                else -> txEnvelope
            }
            out[it.id] = e
            previous = e
        }
        return out
    }

    data class Share(val envelopeId: String, val items: Long, val tax: Long, val total: Long)

    /** Item sums by envelope, in item order. Positive cents. */
    private fun weights(items: List<ReceiptItemEntity>, pick: Map<String, String?>): LinkedHashMap<String, Long> {
        val w = LinkedHashMap<String, Long>()
        for (i in items.sortedBy { it.line }) {
            val e = pick[i.id] ?: throw BudgetRuleException("Choose an envelope for every item.")
            w[e] = (w[e] ?: 0L) + i.amount
        }
        if (w.isEmpty()) throw BudgetRuleException("This receipt has no items to split.")
        if (w.values.any { it <= 0 }) throw BudgetRuleException("A discount can't be on an envelope by itself. Put it with the item it came off.")
        return w
    }

    /** What each envelope gets, for the preview: its items, its share of the tax, and its share of the charge. */
    fun shares(txAmount: Long, items: List<ReceiptItemEntity>, tax: Long, pick: Map<String, String?>): List<Share> {
        val w = weights(items, pick)
        val taxParts = Rules.allocate(tax, w.values.toList())
        val totalParts = Rules.allocate(abs(txAmount), w.values.toList())
        return w.keys.mapIndexed { i, e -> Share(e, w[e]!!, taxParts[i], totalParts[i]) }
    }

    /** The transaction's new lines: the whole charge shared by the item sums, so tax and any tip follow the items. */
    fun lines(txAmount: Long, items: List<ReceiptItemEntity>, pick: Map<String, String?>): List<LineDraft> {
        val w = weights(items, pick)
        return w.keys.zip(Rules.allocate(txAmount, w.values.toList())).filter { it.second != 0L }.map { (e, a) -> LineDraft(e, a) }
    }
}

/** Returning items from a purchase that has a receipt. */
object ItemReturns {

    fun returnedMilli(itemId: String, returns: List<ItemReturnEntity>, except: String? = null) =
        returns.filter { it.itemId == itemId && !it.deleted && it.transactionId != except }.sumOf { it.quantityMilli }

    fun remainingMilli(item: ReceiptItemEntity, returns: List<ItemReturnEntity>, except: String? = null) =
        maxOf(0L, item.quantityMilli - returnedMilli(item.id, returns, except))

    /** What [qtyMilli] of an item is worth with its share of the receipt's tax (spread over all items by price). */
    fun value(item: ReceiptItemEntity, qtyMilli: Long, items: List<ReceiptItemEntity>, tax: Long): Long {
        if (item.quantityMilli <= 0) return 0
        val sum = items.filter { it.amount > 0 }.sumOf { it.amount }
        val withTax = item.amount + if (sum > 0 && item.amount > 0) item.amount * tax / sum else 0
        return withTax * qtyMilli / item.quantityMilli
    }

    /** The refund and what the picked items come to are close enough: within 2%, or 10 cents. */
    fun closeEnough(refund: Long, selected: Long) = abs(refund - selected) <= maxOf(10L, refund * 2 / 100)

    /**
     * Where the refund goes: each picked item's envelope, in proportion to what it's worth. An item without an
     * envelope follows how the purchase itself was split.
     */
    fun lines(
        refund: Long, picks: Map<ReceiptItemEntity, Long>, items: List<ReceiptItemEntity>, tax: Long, purchaseLines: List<Pair<String, Long>>,
    ): Pair<List<LineDraft>, Map<String, Long>> {
        val values = picks.entries.filter { it.value > 0 }.map { (i, q) -> i to value(i, q, items, tax) }
        if (values.isEmpty()) throw BudgetRuleException("Tick at least one item.")
        val perItem = values.map { it.first.id }.zip(Rules.allocate(refund, values.map { maxOf(1L, it.second) })).toMap()
        val w = LinkedHashMap<String, Long>()
        for ((item, _) in values) {
            val part = perItem[item.id]!!
            val e = item.envelopeId
            if (e != null) { w[e] = (w[e] ?: 0L) + part; continue }
            if (purchaseLines.isEmpty()) throw BudgetRuleException("Sort the purchase into an envelope first.")
            val spread = Rules.allocate(part, purchaseLines.map { abs(it.second) })
            purchaseLines.forEachIndexed { k, (env, _) -> w[env] = (w[env] ?: 0L) + spread[k] }
        }
        return w.filterValues { it != 0L }.map { (e, a) -> LineDraft(e, a) } to perItem
    }

    /**
     * A scanned return receipt against the purchase's items: each returned line is matched to a purchase item with the
     * same cleaned name (or one name inside the other) and the same unit price, up to what's still returnable.
     */
    fun matchReturnReceipt(returned: List<ReceiptItemEntity>, purchase: List<ReceiptItemEntity>, returns: List<ItemReturnEntity>): Map<String, Long> {
        val left = purchase.associate { it.id to remainingMilli(it, returns) }.toMutableMap()
        val out = LinkedHashMap<String, Long>()
        for (r in returned) {
            val rUnit = r.unitPrice ?: (r.amount * 1000 / maxOf(1L, r.quantityMilli))
            val p = purchase.firstOrNull { p ->
                val pUnit = p.unitPrice ?: (p.amount * 1000 / maxOf(1L, p.quantityMilli))
                (left[p.id] ?: 0) > 0 && abs(pUnit - rUnit) <= 1 &&
                    (p.name == r.name || (p.name.isNotEmpty() && r.name.isNotEmpty() && (" ${p.name} ".contains(" ${r.name} ") || " ${r.name} ".contains(" ${p.name} "))))
            } ?: continue
            val q = minOf(r.quantityMilli, left[p.id]!!)
            out[p.id] = (out[p.id] ?: 0L) + q
            left[p.id] = left[p.id]!! - q
        }
        return out
    }

    /** The purchase's own envelope lines, for items that have no envelope of their own. */
    fun purchaseLines(b: Budget, purchaseId: String): List<Pair<String, Long>> =
        b.lines.filter { it.transactionId == purchaseId && it.bucket == LineBucket.ENVELOPE && it.envelopeId != null }.map { it.envelopeId!! to it.amount }
}
