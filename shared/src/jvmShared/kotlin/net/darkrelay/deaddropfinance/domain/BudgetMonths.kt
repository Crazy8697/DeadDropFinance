package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BillMonthEntity
import net.darkrelay.deaddropfinance.data.EnvelopeEntity

/**
 * Budget months as Bills sees them (phone 1.9.9, program 0.15.12): payday to payday, the next one not included. The
 * paydays are the pay schedule's dates where one is saved (a payday marked within [Paydays.SAME_PAYDAY_DAYS] days of
 * one stands in for it), otherwise the paydays marked and, past the last, the next payday worked out from them. A
 * scheduled payday that has come counts even before its deposit is marked, so the month that holds today is always
 * the one Bills shows first (0.15.11 ended it the day before an unmarked payday, which hid the next month's bills).
 * With no payday at all, calendar months. Pure.
 */
class BudgetMonths(paydays: List<LocalDate>, schedule: PaySchedule?, reference: LocalDate) {

    /** One budget month: from [start] up to, not including, [end]. */
    data class Window(val start: LocalDate, val end: LocalDate) {
        fun contains(d: LocalDate) = !d.isBefore(start) && d.isBefore(end)
        val days: Long get() = ChronoUnit.DAYS.between(start, end)

        /** "Dec 2026" for a month of paydays; "Nov 27 to Dec 10" for a shorter one. */
        val label: String get() = if (days >= 25) YearMonth.from(start.plusDays(days / 2)).format(MONTH) else "${start.format(DAY)} to ${end.minusDays(1).format(DAY)}"

        /** The Budget screen's next month (1.9.9): "Next budget month, Nov 27 to Dec 30", payday to payday. */
        val nextLabel: String get() = "Next budget month, ${start.format(DAY)} to ${end.format(DAY)}"
    }

    private val bounds: List<LocalDate>

    init {
        val from = reference.minusYears(YEARS)
        val to = reference.plusYears(YEARS)
        val marked = paydays.distinct().sorted()
        val out = sortedSetOf<LocalDate>()
        if (schedule != null) {
            val count = if (schedule.kind == PaySchedule.Kind.MONTHLY) (YEARS * 2 * 12 + 4).toInt() else (YEARS * 2 * 27 + 6).toInt()
            schedule.dates(from, count).filter { s -> marked.none { kotlin.math.abs(ChronoUnit.DAYS.between(it, s)) <= Paydays.SAME_PAYDAY_DAYS } }.forEach { out += it }
            out += marked
        } else if (marked.isNotEmpty()) {
            out += marked
            val p = Paydays.predict(marked, reference)
            fun step(d: LocalDate, k: Long): LocalDate = p.monthlyDay?.let { day ->
                val m = YearMonth.from(d).plusMonths(k)
                m.atDay(minOf(day, m.lengthOfMonth()))
            } ?: d.plusDays((p.intervalDays ?: 14) * k)
            var n = p.next ?: step(marked.last(), 1)
            while (!n.isAfter(to)) { out += n; n = step(n, 1) }
            var b = step(marked.first(), -1)
            while (!b.isBefore(from)) { out += b; b = step(b, -1) }
        } else {
            var m = YearMonth.from(from)
            while (!m.atDay(1).isAfter(to)) { out += m.atDay(1); m = m.plusMonths(1) }
        }
        bounds = out.toList()
    }

    /** The budget month that holds [d]. */
    fun containing(d: LocalDate): Window {
        val i = bounds.indexOfLast { !it.isAfter(d) }
        if (i < 0) return Window(bounds.first().minusMonths(1), bounds.first())
        if (i == bounds.size - 1) return Window(bounds[i], bounds[i].plusMonths(1))
        return Window(bounds[i], bounds[i + 1])
    }

    /** The budget month [k] months after (or before, negative) [w]. */
    fun step(w: Window, k: Int): Window {
        var x = w
        repeat(kotlin.math.abs(k)) { x = if (k > 0) containing(x.end) else containing(x.start.minusDays(1)) }
        return x
    }

    companion object {
        private const val YEARS = 4L
        private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
        private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    }
}

/**
 * A bill's amount for one budget month (1.9.9, program 0.15.12), typed in Bills, Coming up's This month box: the amount
 * that counts for the bill's occurrences in that month (the row, the card, the forecast, the bills calendar, the
 * envelope's target for the month). Exact, never rounded. A row's [BillMonthEntity.monthStart] is its month's first
 * day; a payday marked a few days off the one expected still finds it ([Paydays.SAME_PAYDAY_DAYS]).
 */
object BillMonths {

    /** The saved row for [billId] in month [w] (the box's amount, or null when cleared), if any. */
    fun rowFor(rows: List<BillMonthEntity>, billId: String, w: BudgetMonths.Window): BillMonthEntity? =
        rows.filter { it.billId == billId && !it.deleted && kotlin.math.abs(ChronoUnit.DAYS.between(it.monthStart, w.start)) <= Paydays.SAME_PAYDAY_DAYS }
            .maxByOrNull { it.updatedAt }

    /** The amount typed for [billId] in month [w], or null (the bill's normal amount counts). */
    fun amountFor(rows: List<BillMonthEntity>, billId: String, w: BudgetMonths.Window): Long? = rowFor(rows, billId, w)?.amount

    /** When [billId]'s box was last saved, any month (cleared counts), or null if never. */
    fun lastChecked(rows: List<BillMonthEntity>, billId: String): Long? = rows.filter { it.billId == billId && !it.deleted }.maxOfOrNull { it.updatedAt }

    /** For [bill]'s occurrence due on a date: the amount typed for the month that holds it, or null. */
    fun amountOf(rows: List<BillMonthEntity>, months: BudgetMonths, bill: BillEntity): (LocalDate) -> Long? =
        { due -> if (rows.isEmpty()) null else amountFor(rows, bill.id, months.containing(due)) }

    /**
     * [e]'s target for month [w]: when a bill of the envelope has an amount typed for the month, the sum of its bills'
     * amounts for the month (typed where there is one, the normal amount for the rest); otherwise null (the standing
     * target counts). Its standing target is never changed.
     */
    fun monthTarget(e: EnvelopeEntity, bills: List<BillEntity>, rows: List<BillMonthEntity>, w: BudgetMonths.Window): Long? {
        val mine = bills.filter { it.envelopeId == e.id && !it.deleted }
        if (mine.none { amountFor(rows, it.id, w) != null }) return null
        return mine.sumOf { amountFor(rows, it.id, w) ?: it.amountDue }
    }
}
