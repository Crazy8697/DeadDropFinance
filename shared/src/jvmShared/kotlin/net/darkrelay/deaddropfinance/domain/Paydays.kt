package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The next payday, predicted from the deposits marked as payday (FEATURES.md, Payday). Payday itself is always the
 * deposit landing; this only says when it's expected, for the checklist, autopay warnings and the forecast. The user
 * can correct the prediction. Pure.
 */
object Paydays {

    data class Prediction(
        val last: LocalDate?,
        /** The expected next payday (corrected by the user, or predicted). Null before the first payday. */
        val next: LocalDate?,
        /** Days between paydays, or null when it's monthly on a day of the month. */
        val intervalDays: Long?,
        val monthlyDay: Int?,
        /** Only one payday so far: the next one is a guess (two weeks). */
        val guessed: Boolean,
        /** The user set the date. */
        val corrected: Boolean,
        /** The expected date has passed and the deposit isn't in. */
        val late: Boolean,
    ) {
        /** The day the money is counted on: the next payday, or tomorrow while it's late. */
        fun effectiveNext(today: LocalDate): LocalDate? = next?.let { if (late) today.plusDays(1) else it }
    }

    /** Gaps around this many days are monthly (on a day of the month); others are a fixed number of days. */
    private val monthly = 27L..32L

    fun predict(paydays: List<LocalDate>, today: LocalDate, correction: LocalDate? = null): Prediction {
        val dates = paydays.distinct().sorted()
        val last = dates.lastOrNull() ?: return Prediction(null, null, null, null, guessed = false, corrected = false, late = false)
        val gaps = dates.takeLast(7).zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.filter { it > 0 }
        val median = gaps.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        var interval: Long? = null
        var day: Int? = null
        val predicted = when {
            median == null -> { interval = 14; last.plusDays(14) }
            median in monthly -> {
                // the day of the month it usually comes (the most common of the last few)
                day = dates.takeLast(4).map { it.dayOfMonth }.groupingBy { it }.eachCount().maxByOrNull { (d, n) -> n * 100 + d }!!.key
                val m = last.plusMonths(1)
                m.withDayOfMonth(minOf(day, m.lengthOfMonth()))
            }
            else -> { interval = median; last.plusDays(median) }
        }
        val corrected = correction != null && correction.isAfter(last)
        val next = if (corrected) correction!! else predicted
        return Prediction(last, next, interval, day, guessed = median == null && !corrected, corrected = corrected, late = today.isAfter(next))
    }
}
