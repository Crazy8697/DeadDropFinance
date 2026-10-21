package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import net.darkrelay.deaddropfinance.data.SubscriptionEntity
import net.darkrelay.deaddropfinance.data.SubscriptionStatus
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind

/**
 * Recurring charges found in the transactions (FEATURES.md, Subscriptions): monthly, every 3 months, every 6 months
 * and yearly. A series is one payee's charges (or several payees merged by the user) that come at one of those
 * intervals. The amount shown is always the latest charge, never an average, and a change from the charge before is
 * flagged. Pure.
 */
object Recurring {

    enum class Interval(val months: Int, val minDays: Long, val maxDays: Long, val label: String) {
        MONTHLY(1, 26, 35, "Monthly"),
        QUARTERLY(3, 84, 98, "Every 3 months"),
        SEMIANNUAL(6, 170, 195, "Every 6 months"),
        YEARLY(12, 350, 380, "Yearly"),
    }

    data class Series(
        /** The key the user's choices are kept under: the first of [keys]. */
        val key: String,
        val keys: List<String>,
        val name: String,
        val interval: Interval,
        val charges: List<TransactionEntity>,
        val subscription: SubscriptionEntity?,
    ) {
        val latest get() = charges.last()
        /** The current amount, from the latest charge (positive cents). */
        val amount: Long get() = -latest.amount
        val previousAmount: Long? get() = charges.getOrNull(charges.size - 2)?.let { -it.amount }
        /** Up (positive) or down since the charge before; null when it didn't change. */
        val priceChange: Long? get() = previousAmount?.let { p -> (amount - p).takeIf { it != 0L } }
        val lastDate: LocalDate get() = latest.date
        val nextDate: LocalDate get() = lastDate.plusMonths(interval.months.toLong())
        /** What it costs per month (a yearly charge divided by 12, and so on), in cents. */
        val monthly: Long get() = Budget.ceilDiv(amount, interval.months.toLong())
        val isNew: Boolean get() = subscription == null || subscription.status == SubscriptionStatus.NEW
        val dismissed: Boolean get() = subscription?.status == SubscriptionStatus.DISMISSED
    }

    /**
     * Finds the series in [transactions] (spending only). [subscriptions] carry the user's merges, names and choices;
     * dismissed ones are still returned (marked), so they can be brought back.
     */
    fun detect(transactions: List<TransactionEntity>, subscriptions: List<SubscriptionEntity>): List<Series> {
        val subs = subscriptions.filter { !it.deleted }
        // a merged subscription pulls all its keys into one group
        val groupOf = HashMap<String, String>()
        for (s in subs) { val ks = s.keys; ks.forEach { groupOf[it] = ks.first() } }
        val spends = transactions.filter { it.kind == TransactionKind.NORMAL && it.amount < 0 && !it.isWash && !it.deleted }
        val groups = spends.groupBy { t -> Payees.key(t).let { groupOf[it] ?: it } }.filterKeys { it.isNotEmpty() }
        val out = mutableListOf<Series>()
        for ((key, list) in groups) {
            val charges = list.sortedWith(compareBy({ it.date }, { it.createdAt })).distinctBy { it.date }
            if (charges.size < 2) continue
            val interval = classify(charges) ?: continue
            val sub = subs.firstOrNull { key in it.keys }
            val keys = sub?.keys ?: listOf(key)
            out += Series(key, keys, sub?.name?.takeIf { it.isNotBlank() } ?: charges.last().payee.ifBlank { key }, interval, charges, sub)
        }
        return out.sortedByDescending { it.monthly }
    }

    /**
     * The interval the charges keep, or null. Most gaps (at least 4 in 5) must fit it; monthly needs three charges,
     * or two of the same amount. Amounts may change (price rises) but not wildly: the latest is within half of the
     * typical one, so a store visited now and then isn't taken for a subscription.
     */
    fun classify(charges: List<TransactionEntity>): Interval? {
        val gaps = charges.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        if (gaps.isEmpty()) return null
        val amounts = charges.map { -it.amount }.sorted()
        val typical = amounts[amounts.size / 2]
        if (typical <= 0) return null
        if (charges.takeLast(3).any { val a = -it.amount; a * 2 < typical || a > typical * 2 }) return null
        for (i in Interval.entries) {
            val fit = gaps.count { it in i.minDays..i.maxDays }
            val tooClose = gaps.count { it < i.minDays }
            if (fit == 0 || fit * 5 < gaps.size * 4 || tooClose > 0) continue
            if (i == Interval.MONTHLY && charges.size < 3 && charges[0].amount != charges[1].amount) continue
            return i
        }
        return null
    }

    /** All the monthly equivalents together, for the subscriptions total (dismissed ones left out). */
    fun monthlyTotal(series: List<Series>): Long = series.filter { !it.dismissed }.sumOf { it.monthly }
}
