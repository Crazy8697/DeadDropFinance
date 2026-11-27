package net.darkrelay.deaddropfinance.ui

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BillPaymentEntity
import net.darkrelay.deaddropfinance.data.PaymentMethod
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.PaySchedule
import net.darkrelay.deaddropfinance.domain.Paydays
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bills, Coming up (1.9.8, program 0.15.11): this budget month's unpaid bills, due before the next payday (the pay
 * schedule's, or the one worked out without it), with anything still unpaid from before listed as overdue. Invented
 * bills only.
 */
class ComingUpWindowTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun bill(id: String, due: String, amount: Long = 100_00) = BillEntity(id = id, name = "Sample $id", envelopeId = null, amountDue = amount, dueDate = d(due), createdAt = 1, updatedAt = 1)
    private fun paid(billId: String, period: String) = BillPaymentEntity(billId = billId, transactionId = null, amount = 100_00, date = d(period), periodDue = d(period), method = PaymentMethod.CASH, createdAt = 1, updatedAt = 1)
    private fun budget(bills: List<BillEntity>, payments: List<BillPaymentEntity> = emptyList()) = Budget(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), bills = bills, billPayments = payments)
    private fun dues(b: Budget, today: String, next: String?) = comingUp(b, d(today), next?.let(::d)).map { it.name to it.due.toString() }

    private val monthly = PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2)

    @Test fun aMonthlyBillOnThe27thShowsOnceInTheBudgetMonth() {
        // budget month Nov 27 to Dec 30; this month's Nov 27 one paid: only Dec 27
        val b = budget(listOf(bill("tel", "2026-11-27")), listOf(paid("tel", "2026-11-27")))
        assertEquals(listOf("Sample tel" to "2026-12-27"), dues(b, "2026-11-27", "2026-12-30"))
        // left unpaid, the Nov 27 one is this budget month's too (due on its first day): both are listed
        val unpaid = budget(listOf(bill("tel", "2026-11-27")))
        assertEquals(listOf("Sample tel" to "2026-11-27", "Sample tel" to "2026-12-27"), dues(unpaid, "2026-11-27", "2026-12-30"))
    }

    @Test fun aBillDueOnTheNextPaydayIsNotListed() {
        val b = budget(listOf(bill("rent", "2026-12-30"), bill("gym", "2026-12-29")))
        assertEquals(listOf("Sample gym" to "2026-12-29"), dues(b, "2026-11-27", "2026-12-30"))
    }

    @Test fun anUnpaidBillFromLastBudgetMonthIsListedOverdue() {
        val b = budget(listOf(bill("water", "2026-11-20")))
        val items = comingUp(b, d("2026-11-27"), d("2026-12-30"))
        assertEquals(listOf("2026-11-20", "2026-12-20"), items.map { it.due.toString() })
        assertEquals(true, items.first().due.isBefore(d("2026-11-27")))
    }

    @Test fun markingPaydayMovesTheWindow() {
        val b = budget(listOf(bill("tel", "2026-11-27"), bill("gym", "2026-12-15")))
        // Oct 30 the last payday marked, today Nov 26: the budget month ends before Nov 27
        val before = Paydays.predict(listOf(d("2026-10-30")), d("2026-11-26"), schedule = monthly).effectiveNext(d("2026-11-26"))
        assertEquals(d("2026-11-27"), before)
        assertEquals(emptyList<Pair<String, String>>(), dues(b, "2026-11-26", before.toString()))
        // Nov 27 marked: the window runs to Dec 30
        val after = Paydays.predict(listOf(d("2026-10-30"), d("2026-11-27")), d("2026-11-27"), schedule = monthly).effectiveNext(d("2026-11-27"))
        assertEquals(d("2026-12-30"), after)
        assertEquals(listOf("2026-11-27", "2026-12-15", "2026-12-27"), dues(b, "2026-11-27", after.toString()).map { it.second })
    }

    @Test fun noScheduleFallsBackAsToday() {
        val b = budget(listOf(bill("tel", "2026-11-27"), bill("gym", "2026-12-15")))
        // two paydays two weeks apart, no schedule: the next payday the app works out, Nov 27
        val next = Paydays.predict(listOf(d("2026-10-30"), d("2026-11-13")), d("2026-11-14"))
        assertEquals(d("2026-11-27"), next.next)
        // the window ends the day before it: nothing is due yet
        assertEquals(emptyList<String>(), dues(b, "2026-11-14", next.effectiveNext(d("2026-11-14")).toString()).map { it.second })
        // no payday at all: the next 45 days
        assertEquals(listOf("2026-11-27", "2026-12-15", "2026-12-27"), dues(b, "2026-11-14", null).map { it.second })
    }
}
