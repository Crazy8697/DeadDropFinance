package net.darkrelay.deaddropfinance.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters
import org.json.JSONObject

/**
 * The user's pay schedule (phone 1.9.8, program 0.15.11), set in the Payday schedule popup: monthly on a day of the
 * month (with the days the bank pays early), or every two weeks from a payday. A payday that lands on a weekend or a
 * Federal Reserve bank holiday moves to the business day before it (or after it). Once saved it decides the next
 * payday; the guess from the gaps between paydays is never used again for that budget. Kept in the budget's synced
 * settings as JSON ([toJson]). Pure.
 */
data class PaySchedule(
    val kind: Kind,
    /** Monthly: the official day of the month, 1 to 31 (past a short month's end: its last day). */
    val dayOfMonth: Int = 1,
    /** Monthly: how many calendar days before the official date the bank pays, 0 to 5. */
    val daysEarly: Int = 0,
    /** A payday on a weekend or bank holiday moves to the business day after it instead of before it. */
    val moveLater: Boolean = false,
    /** Every two weeks: one payday of the run (the next one, as entered). */
    val anchor: LocalDate? = null,
) {
    enum class Kind { MONTHLY, BIWEEKLY }

    /** The paydays on or after [from], in order, [count] of them. */
    fun dates(from: LocalDate, count: Int): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        when (kind) {
            Kind.MONTHLY -> {
                // a month's payday can land in the month before it (days early, moved earlier): start a month back
                var m = from.withDayOfMonth(1).minusMonths(1)
                while (out.size < count) {
                    val d = monthly(m.year, m.monthValue)
                    if (!d.isBefore(from)) out += d
                    m = m.plusMonths(1)
                }
            }
            Kind.BIWEEKLY -> {
                val a = anchor ?: return emptyList()
                // the run's official dates, two weeks apart, from a little before [from]
                var nominal = a
                while (nominal.isAfter(from.minusDays(14))) nominal = nominal.minusDays(14)
                while (out.size < count) {
                    val d = businessDay(nominal, moveLater)
                    if (!d.isBefore(from)) out += d
                    nominal = nominal.plusDays(14)
                }
            }
        }
        return out.distinct().sorted().take(count)
    }

    /** The next payday on or after [from]. */
    fun next(from: LocalDate): LocalDate = dates(from, 1).single()

    /** The payday of month [month] (1 to 12) of [year]: the official date, the days early, then a business day. */
    fun monthly(year: Int, month: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        val official = first.withDayOfMonth(minOf(dayOfMonth.coerceIn(1, 31), first.lengthOfMonth()))
        return businessDay(official.minusDays(daysEarly.coerceIn(0, MAX_DAYS_EARLY).toLong()), moveLater)
    }

    /** "Monthly, the 1st, 2 days early", "Every two weeks": what the Bills card says. */
    fun describe(): String = when (kind) {
        Kind.MONTHLY -> buildString {
            append("Monthly, the ${ordinal(dayOfMonth)}")
            if (daysEarly > 0) append(", $daysEarly day${if (daysEarly == 1) "" else "s"} early")
            if (moveLater) append(", later on a weekend or holiday")
        }
        Kind.BIWEEKLY -> if (moveLater) "Every two weeks, later on a weekend or holiday" else "Every two weeks"
    }

    fun toJson(): String = JSONObject()
        .put("kind", kind.name).put("day", dayOfMonth).put("early", daysEarly).put("later", moveLater)
        .put("anchor", anchor?.toString() ?: JSONObject.NULL).toString()

    companion object {
        const val MAX_DAYS_EARLY = 5

        /** The schedule saved as [toJson], or null when there's none (or it can't be read). */
        fun fromJson(s: String?): PaySchedule? = runCatching {
            if (s.isNullOrBlank()) return null
            val o = JSONObject(s)
            val kind = Kind.valueOf(o.getString("kind"))
            val anchor = o.optString("anchor").takeIf { it.isNotBlank() && it != "null" }?.let { LocalDate.parse(it) }
            if (kind == Kind.BIWEEKLY && anchor == null) return null
            PaySchedule(kind, o.optInt("day", 1).coerceIn(1, 31), o.optInt("early", 0).coerceIn(0, MAX_DAYS_EARLY), o.optBoolean("later", false), anchor)
        }.getOrNull()

        /** [d] if it's a business day, else the business day before it (or after it, [later]). */
        fun businessDay(d: LocalDate, later: Boolean): LocalDate {
            var x = d
            while (!isBusinessDay(x)) x = if (later) x.plusDays(1) else x.minusDays(1)
            return x
        }

        fun isBusinessDay(d: LocalDate) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && !BankHolidays.isHoliday(d)

        fun ordinal(n: Int): String = "$n" + when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
    }
}

/**
 * The US Federal Reserve bank holidays, by rule, for any year (no list to update, no network). The fixed-date ones
 * (New Year's Day, Juneteenth from 2022, Independence Day, Veterans Day, Christmas) are observed the Monday after when
 * they fall on a Sunday; on a Saturday the Fed stays open the Friday before, so nothing is added. The floating ones:
 * Martin Luther King Jr. Day (third Monday of January), Washington's Birthday (third Monday of February), Memorial Day
 * (last Monday of May), Labor Day (first Monday of September), Columbus Day (second Monday of October) and Thanksgiving
 * (fourth Thursday of November).
 */
object BankHolidays {

    fun isHoliday(d: LocalDate): Boolean = d in of(d.year)

    private val cache = java.util.concurrent.ConcurrentHashMap<Int, Set<LocalDate>>()

    /** The days the Fed is closed for a holiday in [year]. */
    fun of(year: Int): Set<LocalDate> = cache.getOrPut(year) {
        fun nth(month: Month, day: DayOfWeek, n: Int) = LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))
        fun observed(d: LocalDate): LocalDate? = when (d.dayOfWeek) {
            DayOfWeek.SUNDAY -> d.plusDays(1)
            DayOfWeek.SATURDAY -> null
            else -> d
        }
        val fixed = listOfNotNull(
            LocalDate.of(year, 1, 1),
            if (year >= 2022) LocalDate.of(year, 6, 19) else null,
            LocalDate.of(year, 7, 4),
            LocalDate.of(year, 11, 11),
            LocalDate.of(year, 12, 25),
        ).mapNotNull { observed(it) }
        val floating = listOf(
            nth(Month.JANUARY, DayOfWeek.MONDAY, 3),
            nth(Month.FEBRUARY, DayOfWeek.MONDAY, 3),
            LocalDate.of(year, 5, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)),
            nth(Month.SEPTEMBER, DayOfWeek.MONDAY, 1),
            nth(Month.OCTOBER, DayOfWeek.MONDAY, 2),
            nth(Month.NOVEMBER, DayOfWeek.THURSDAY, 4),
        )
        (fixed + floating).toSet()
    }
}
