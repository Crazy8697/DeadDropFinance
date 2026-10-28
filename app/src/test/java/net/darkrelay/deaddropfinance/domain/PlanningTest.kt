package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BillRecurrence
import net.darkrelay.deaddropfinance.data.SubscriptionEntity
import net.darkrelay.deaddropfinance.data.SubscriptionStatus
import net.darkrelay.deaddropfinance.data.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Next payday, subscriptions and the balance forecast, on invented data. */
class PlanningTest {

    private fun d(m: Int, day: Int) = LocalDate.of(2030, m, day)

    // ---- paydays ----------------------------------------------------------------------------------------------------

    @Test fun monthlyPaydaysPredictTheUsualDayAndALatePaydayIsNoted() {
        val p = Paydays.predict(listOf(d(1, 15), d(2, 14), d(3, 15)), d(3, 20))
        assertEquals(15, p.monthlyDay)
        assertEquals(d(4, 15), p.next)
        assertFalse(p.late)
        val late = Paydays.predict(listOf(d(1, 15), d(2, 14), d(3, 15)), d(4, 17))
        assertTrue(late.late)
        assertEquals("while it's late the money is counted from tomorrow", d(4, 18), late.effectiveNext(d(4, 17)))
        // the user corrects it
        val fixed = Paydays.predict(listOf(d(1, 15), d(2, 14), d(3, 15)), d(4, 17), correction = d(4, 19))
        assertTrue(fixed.corrected)
        assertEquals(d(4, 19), fixed.next)
        assertFalse(fixed.late)
        // a correction before the last payday is old and ignored
        assertFalse(Paydays.predict(listOf(d(1, 15), d(2, 14), d(3, 15)), d(3, 20), correction = d(3, 1)).corrected)
    }

    @Test fun everyOtherWeekAndASinglePayday() {
        val p = Paydays.predict(listOf(d(1, 3), d(1, 17), d(1, 31), d(2, 14)), d(2, 20))
        assertEquals(14L, p.intervalDays)
        assertEquals(d(2, 28), p.next)
        val one = Paydays.predict(listOf(d(2, 14)), d(2, 15))
        assertTrue(one.guessed)
        assertEquals(d(2, 28), one.next)
        assertNull(Paydays.predict(emptyList(), d(2, 15)).next)
    }

    // ---- subscriptions ----------------------------------------------------------------------------------------------

    private var n = 0
    private fun charge(payee: String, amount: Long, day: LocalDate) =
        TransactionEntity(id = "c${n++}", accountId = "chk", date = day, payee = payee, importDescription = payee, amount = -amount, createdAt = n.toLong(), updatedAt = 0)

    @Test fun subscriptionsAtEveryIntervalWithPriceChangesAndNew() {
        val txs = listOf(
            charge("SAMPLE STREAMING", 9_99, d(1, 5)), charge("SAMPLE STREAMING", 9_99, d(2, 5)), charge("SAMPLE STREAMING", 11_49, d(3, 5)),
            charge("SAMPLE CLOUD BACKUP", 30_00, d(1, 10)), charge("SAMPLE CLOUD BACKUP", 30_00, d(4, 10)),
            charge("SAMPLE INSURANCE CO", 240_00, d(1, 20)), charge("SAMPLE INSURANCE CO", 240_00, d(7, 20)),
            charge("SAMPLE DOMAIN HOST", 15_00, LocalDate.of(2029, 2, 1)), charge("SAMPLE DOMAIN HOST", 18_00, d(2, 1)),
            // a store visited every week or so isn't a subscription
            charge("SAMPLE GROCER", 45_00, d(1, 3)), charge("SAMPLE GROCER", 61_20, d(1, 10)), charge("SAMPLE GROCER", 38_75, d(1, 17)), charge("SAMPLE GROCER", 52_00, d(2, 17)),
            // two charges a month apart with different amounts aren't enough
            charge("SAMPLE DINER", 22_00, d(1, 8)), charge("SAMPLE DINER", 31_00, d(2, 8)),
        )
        val found = Recurring.detect(txs, emptyList()).associateBy { it.key }
        assertEquals(setOf("sample streaming", "sample cloud backup", "sample insurance", "sample domain host"), found.keys)
        val s = found["sample streaming"]!!
        assertEquals(Recurring.Interval.MONTHLY, s.interval)
        assertEquals("the latest charge, never an average", 11_49L, s.amount)
        assertEquals(1_50L, s.priceChange)
        assertEquals(d(4, 5), s.nextDate)
        assertTrue(s.isNew)
        assertEquals(Recurring.Interval.QUARTERLY, found["sample cloud backup"]!!.interval)
        assertEquals(10_00L, found["sample cloud backup"]!!.monthly)
        assertEquals(Recurring.Interval.SEMIANNUAL, found["sample insurance"]!!.interval)
        assertEquals(40_00L, found["sample insurance"]!!.monthly)
        val y = found["sample domain host"]!!
        assertEquals(Recurring.Interval.YEARLY, y.interval)
        assertEquals(1_50L, y.monthly)
        assertEquals(3_00L, y.priceChange)
        assertEquals(11_49L + 10_00 + 40_00 + 1_50, Recurring.monthlyTotal(found.values.toList()))
        // the user's choices: confirmed isn't new, dismissed is left out of the total, merged keys make one series
        val subs = listOf(
            SubscriptionEntity(matchKey = "sample streaming", status = SubscriptionStatus.CONFIRMED, name = "My Stream", createdAt = 0, updatedAt = 0),
            SubscriptionEntity(matchKey = "sample insurance", status = SubscriptionStatus.DISMISSED, createdAt = 0, updatedAt = 0),
            SubscriptionEntity(matchKey = "sample music;sample music plus", status = SubscriptionStatus.CONFIRMED, createdAt = 0, updatedAt = 0),
        )
        val more = txs + listOf(charge("SAMPLE MUSIC", 8_00, d(1, 2)), charge("SAMPLE MUSIC PLUS", 8_00, d(2, 2)), charge("SAMPLE MUSIC PLUS", 8_00, d(3, 2)))
        val again = Recurring.detect(more, subs).associateBy { it.key }
        assertFalse(again["sample streaming"]!!.isNew)
        assertEquals("My Stream", again["sample streaming"]!!.name)
        assertTrue(again["sample insurance"]!!.dismissed)
        assertEquals(3, again["sample music"]!!.charges.size)
        assertEquals(11_49L + 10_00 + 1_50 + 8_00, Recurring.monthlyTotal(again.values.toList()))
    }

    // ---- forecast ---------------------------------------------------------------------------------------------------

    /**
     * Three past budget months (payday on the 1st), each spending 300.00 in its first three days and 100.00 spread
     * later, and now: 1000.00 in the bank on the 2nd, an everyday envelope holding 230.00, a bill and an autopay due.
     */
    private fun budget(history: Boolean = true): Budget {
        val txs = mutableListOf<TransactionEntity>()
        val lines = mutableListOf<net.darkrelay.deaddropfinance.data.TransactionLineEntity>()
        var i = 0
        for (m in 1..4) {
            if (!history && m < 4) continue
            val pay = F.tx("pay$m", "chk", 1, d(m, 1), payday = true)
            txs += pay; lines += F.line(pay.id, 1, rta = true)
            if (m == 4) continue
            for (day in 0..2) { val t = F.tx("s${i++}", "chk", -100_00, d(m, 1).plusDays(day.toLong()), payee = "SAMPLE SHOP $i"); txs += t; lines += F.line(t.id, t.amount, "food") }
            for (day in listOf(10, 20)) { val t = F.tx("s${i++}", "chk", -50_00, d(m, day), payee = "SAMPLE SHOP $i"); txs += t; lines += F.line(t.id, t.amount, "food") }
        }
        val acct = F.account("chk", 1000_00).copy(startDate = d(1, 1))
        // the history above moves the balance; set the start so today's balance is 1000.00
        val moved = txs.sumOf { it.amount }
        val food = F.envelope("food").copy(everyday = true)
        val bills = listOf(
            BillEntity(id = "b1", name = "Sample Rent", envelopeId = null, amountDue = 600_00, dueDate = d(4, 5), recurrence = BillRecurrence.MONTHLY, createdAt = 0, updatedAt = 0),
            BillEntity(id = "b2", name = "Sample Insurance", envelopeId = null, amountDue = 160_00, dueDate = d(4, 12), recurrence = BillRecurrence.MONTHLY, autopay = true, createdAt = 0, updatedAt = 0),
        )
        return Budget(listOf(acct.copy(startingBalance = 1000_00 - moved)), listOf(F.rollover(), food), txs, lines, listOf(F.move(null, "food", 230_00 + 1200_00)), bills)
    }

    @Test fun planSpreadsEverydayMoneyAndUsualPaceFollowsHistoryWithTheFirstDayBelowZero() {
        val b = budget()
        val f = Forecast.build(b, emptyList(), d(4, 2), d(4, 15))
        assertEquals(1000_00L, f.plan.start)
        assertEquals(13, f.plan.days.size)
        assertEquals(d(4, 14), f.plan.days.last().date)
        // Plan: 230.00 over 13 days, the bills on their dates
        assertEquals(230_00L, f.plan.days.sumOf { it.spending })
        assertEquals(1000_00L - 230_00 - 600_00 - 160_00, f.plan.end)
        assertEquals(listOf("Sample Rent"), f.plan.days.first { it.date == d(4, 5) }.fixed.map { it.label })
        assertEquals("Sample Insurance (autopay)", f.plan.days.first { it.date == d(4, 12) }.fixed.single().label)
        assertNull(f.plan.firstNegative)
        // Usual pace: 100.00 a day on days 2 and 3 after payday, 50.00 on the 10th: below zero by the autopay
        val u = f.usual!!
        assertEquals(100_00L, u.days[0].spending)
        assertEquals(100_00L, u.days[1].spending)
        assertEquals(0L, u.days[2].spending)
        assertEquals(50_00L, u.days.first { it.date == d(4, 10) }.spending)
        assertEquals(d(4, 12), u.firstNegative!!.date)
        assertNull(f.usualWhyNot)
    }

    @Test fun tooLittleHistoryShowsOnlyPlanAndSaysWhy() {
        val f = Forecast.build(budget(history = false), emptyList(), d(4, 2), d(4, 15))
        assertNull(f.usual)
        assertNotNull(f.usualWhyNot)
        assertTrue(f.usualWhyNot!!.contains("2 full budget months"))
        assertFalse(f.noEverydayEnvelopes)
    }

    @Test fun paidBillsDropOffAndTrackOnlyAccountsDontCount() {
        val b0 = budget()
        val paid = net.darkrelay.deaddropfinance.data.BillPaymentEntity(billId = "b1", transactionId = null, amount = 600_00, date = d(4, 2), periodDue = d(4, 5), createdAt = 0, updatedAt = 0)
        val track = F.account("save", 5000_00).copy(trackOnly = true)
        val b = Budget(b0.accounts + track, b0.envelopes, b0.transactions, b0.lines, b0.moves, b0.bills, listOf(paid))
        val f = Forecast.build(b, emptyList(), d(4, 2), d(4, 15))
        assertEquals(1000_00L, f.plan.start)
        assertTrue(f.plan.days.none { day -> day.fixed.any { it.label == "Sample Rent" } })
    }
}
