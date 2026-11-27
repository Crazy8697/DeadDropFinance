package net.darkrelay.deaddropfinance.domain

import java.time.DayOfWeek
import java.time.LocalDate
import net.darkrelay.deaddropfinance.domain.PaySchedule.Kind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pay schedule (1.9.8, program 0.15.11) and the Federal Reserve bank holidays it steps around. Invented schedules;
 * the holiday table is the Fed's published 2026 to 2030 FedCash holiday schedule (frbservices.org, Holiday Schedules),
 * typed in here on its own so the rules are checked against it, not against themselves.
 */
class PayScheduleTest {

    private fun d(s: String) = LocalDate.parse(s)

    /** The Fed's published closings, 2026 to 2030: a Saturday holiday is no closing (open the Friday before), a Sunday one closes the Monday. */
    private val published = mapOf(
        2026 to listOf("2026-01-01", "2026-01-19", "2026-02-16", "2026-05-25", "2026-06-19", "2026-09-07", "2026-10-12", "2026-11-11", "2026-11-26", "2026-12-25"),
        2027 to listOf("2027-01-01", "2027-01-18", "2027-02-15", "2027-05-31", "2027-07-05", "2027-09-06", "2027-10-11", "2027-11-11", "2027-11-25"),
        2028 to listOf("2028-01-17", "2028-02-21", "2028-05-29", "2028-06-19", "2028-07-04", "2028-09-04", "2028-10-09", "2028-11-23", "2028-12-25"),
        2029 to listOf("2029-01-01", "2029-01-15", "2029-02-19", "2029-05-28", "2029-06-19", "2029-07-04", "2029-09-03", "2029-10-08", "2029-11-12", "2029-11-22", "2029-12-25"),
        2030 to listOf("2030-01-01", "2030-01-21", "2030-02-18", "2030-05-27", "2030-06-19", "2030-07-04", "2030-09-02", "2030-10-14", "2030-11-11", "2030-11-28", "2030-12-25"),
    ).mapValues { (_, v) -> v.map(::d).toSet() }

    private fun weekend(x: LocalDate) = x.dayOfWeek == DayOfWeek.SATURDAY || x.dayOfWeek == DayOfWeek.SUNDAY
    private fun closed(x: LocalDate) = weekend(x) || x in published.getValue(x.year)

    /** The rule written out step by step against the published table: official date, days early, then back over closed days. */
    private fun byHand(official: LocalDate, early: Long): LocalDate {
        var x = official.minusDays(early)
        while (closed(x)) x = x.minusDays(1)
        return x
    }

    private val firstMinus2 = PaySchedule(Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2, moveLater = false)

    @Test fun theFedHolidays2026To2030AreThePublishedOnes() {
        for ((year, days) in published) assertEquals(days, BankHolidays.of(year), "$year")
        // the Saturday ones are not closings
        assertFalse(BankHolidays.isHoliday(d("2026-07-03")) || BankHolidays.isHoliday(d("2026-07-04")))
        assertFalse(BankHolidays.isHoliday(d("2027-12-24")) || BankHolidays.isHoliday(d("2027-12-31")))
    }

    @Test fun monthlyOnTheFirstTwoDaysEarlyMovedEarlier() {
        val three = firstMinus2.dates(d("2026-11-27"), 3)
        assertEquals(listOf(d("2026-11-27"), d("2026-12-30"), d("2027-01-29")), three)
        // each against the rule: Dec 1 less 2 is Sun Nov 29, so Fri Nov 27; Jan 1 less 2 is Wed Dec 30; Feb 1 less 2 is Sat Jan 30, so Fri Jan 29
        assertEquals(byHand(d("2026-12-01"), 2), three[0])
        assertEquals(byHand(d("2027-01-01"), 2), three[1])
        assertEquals(byHand(d("2027-02-01"), 2), three[2])
        assertEquals(DayOfWeek.FRIDAY, three[0].dayOfWeek)
        // and as the next payday once Nov 27 is marked
        assertEquals(d("2026-12-30"), Paydays.predict(listOf(d("2026-10-30"), d("2026-11-27")), d("2026-11-28"), schedule = firstMinus2).next)
        assertEquals("Monthly, the 1st, 2 days early", firstMinus2.describe())
    }

    @Test fun twentyFourMonthsNeverLandOnAWeekendOrHoliday() {
        val run = firstMinus2.dates(d("2026-11-27"), 24)
        assertEquals(24, run.size)
        assertEquals(run.sorted(), run)
        for ((i, x) in run.withIndex()) {
            assertFalse(weekend(x), "$x is a weekend")
            assertFalse(x in published.getValue(x.year), "$x is a Fed holiday")
            // the month it's for: the payday before the 1st of the month after it
            val official = d("2026-12-01").plusMonths(i.toLong())
            assertEquals(byHand(official, 2), x, "for $official")
        }
    }

    @Test fun day31InShortMonths() {
        val s = PaySchedule(Kind.MONTHLY, dayOfMonth = 31)
        assertEquals(d("2027-04-30"), s.monthly(2027, 4))                  // April's last day, a Friday
        assertEquals(d("2027-02-26"), s.monthly(2027, 2))                  // Feb 28 2027 is a Sunday: the Friday before
        assertEquals(d("2028-02-29"), s.monthly(2028, 2))                  // a leap year's last day, a Tuesday
        assertEquals(d("2027-03-01"), s.copy(moveLater = true).monthly(2027, 2)) // Move later: the Monday after
    }

    @Test fun moveLaterAndNoDaysEarly() {
        val earlier = PaySchedule(Kind.MONTHLY, dayOfMonth = 1)
        val later = earlier.copy(moveLater = true)
        // Jan 1 2027 is a Fed holiday (a Friday): Thursday before, or Monday after (past the weekend)
        assertEquals(d("2026-12-31"), earlier.monthly(2027, 1))
        assertEquals(d("2027-01-04"), later.monthly(2027, 1))
        // a plain business day stays put
        assertEquals(d("2026-12-01"), earlier.monthly(2026, 12))
        assertEquals(d("2026-12-01"), later.monthly(2026, 12))
        assertEquals("Monthly, the 1st, later on a weekend or holiday", later.describe())
    }

    @Test fun everyTwoWeeksAcrossHolidays() {
        val s = PaySchedule(Kind.BIWEEKLY, anchor = d("2026-11-13"))
        // Nov 27 is a business day (the day after Thanksgiving); Dec 25 is Christmas, a Friday: Thursday Dec 24
        assertEquals(listOf(d("2026-11-13"), d("2026-11-27"), d("2026-12-11"), d("2026-12-24"), d("2027-01-08")), s.dates(d("2026-11-13"), 5))
        assertEquals(d("2026-12-28"), s.copy(moveLater = true).dates(d("2026-12-20"), 1).single())
        // Thanksgiving itself: the Wednesday before
        assertEquals(d("2026-11-25"), PaySchedule(Kind.BIWEEKLY, anchor = d("2026-11-12")).dates(d("2026-11-20"), 1).single())
        // from before the anchor too: the run goes back two weeks at a time
        assertEquals(d("2026-10-30"), s.dates(d("2026-10-28"), 1).single())
        assertEquals("Every two weeks", s.describe())
    }

    @Test fun theNextPaydayFollowsTheSchedule() {
        // none marked yet: from today
        assertEquals(d("2026-12-30"), Paydays.predict(emptyList(), d("2026-11-28"), schedule = firstMinus2).next)
        // marked a day early (Nov 26 for the Nov 27 one): that one, so the next is Dec 30
        assertEquals(d("2026-12-30"), Paydays.predict(listOf(d("2026-11-26")), d("2026-11-27"), schedule = firstMinus2).next)
        // the last one marked Oct 30: Nov 27 is next, late after it, never a guess
        val p = Paydays.predict(listOf(d("2026-10-30")), d("2026-11-28"), schedule = firstMinus2)
        assertEquals(d("2026-11-27"), p.next)
        assertTrue(p.late)
        assertFalse(p.guessed)
        assertEquals(firstMinus2, p.schedule)
        // a date set by hand before is no longer used once there's a schedule
        assertEquals(d("2026-11-27"), Paydays.predict(listOf(d("2026-10-30")), d("2026-11-20"), correction = d("2026-11-13"), schedule = firstMinus2).next)
    }

    @Test fun withoutAScheduleNothingChanges() {
        val one = Paydays.predict(listOf(d("2026-11-13")), d("2026-11-14"))
        assertEquals(d("2026-11-27"), one.next)
        assertTrue(one.guessed)
        assertNull(one.schedule)
        val monthly = Paydays.predict(listOf(d("2026-09-01"), d("2026-10-01"), d("2026-11-01")), d("2026-11-14"))
        assertEquals(d("2026-12-01"), monthly.next)
        assertEquals(1, monthly.monthlyDay)
        assertEquals(d("2026-11-20"), Paydays.predict(listOf(d("2026-11-13")), d("2026-11-14"), correction = d("2026-11-20")).next)
    }

    @Test fun savedAsJsonAndReadBack() {
        for (s in listOf(firstMinus2, firstMinus2.copy(moveLater = true), PaySchedule(Kind.BIWEEKLY, anchor = d("2026-11-13")), PaySchedule(Kind.MONTHLY, dayOfMonth = 31))) {
            assertEquals(s, PaySchedule.fromJson(s.toJson()))
        }
        assertNull(PaySchedule.fromJson(null))
        assertNull(PaySchedule.fromJson(""))
        assertNull(PaySchedule.fromJson("not json"))
        assertNull(PaySchedule.fromJson("""{"kind":"BIWEEKLY"}"""), "every two weeks needs its payday")
    }
}
