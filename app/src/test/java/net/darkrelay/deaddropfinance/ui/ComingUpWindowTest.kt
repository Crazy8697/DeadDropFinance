package net.darkrelay.deaddropfinance.ui

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BillPaymentEntity
import net.darkrelay.deaddropfinance.data.PaymentMethod
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.PaySchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bills, Coming up (1.9.9, program 0.15.12): one budget month at a time, each bill's earliest unpaid occurrence in it,
 * anything still unpaid from before listed as overdue in the month that holds today. Invented bills only.
 */
class ComingUpWindowTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun bill(id: String, due: String, amount: Long = 100_00) = BillEntity(id = id, name = "Sample $id", envelopeId = null, amountDue = amount, dueDate = d(due), createdAt = 1, updatedAt = 1)
    private fun paid(billId: String, period: String) = BillPaymentEntity(billId = billId, transactionId = null, amount = 100_00, date = d(period), periodDue = d(period), method = PaymentMethod.CASH, createdAt = 1, updatedAt = 1)
    private fun payday(date: String) = TransactionEntity(id = "pay-$date", accountId = "a", date = d(date), payee = "Sample Payroll Co", amount = 2_000_00, isPayday = true, createdAt = 1, updatedAt = 1)
    private fun budget(bills: List<BillEntity>, payments: List<BillPaymentEntity> = emptyList(), paydays: List<String> = listOf("2026-10-30"), schedule: PaySchedule? = monthly) =
        Budget(emptyList(), emptyList(), paydays.map(::payday), emptyList(), emptyList(), bills = bills, billPayments = payments, paySchedule = schedule)
    private fun dues(b: Budget, today: String, offset: Int = 0) =
        comingUp(b, d(today), comingUpView(b, d(today), ComingUpState().also { it.offset = offset })).map { it.name to it.due.toString() }

    private val monthly = PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2)

    @Test fun aMonthlyBillOnThe27thShowsOnceAMonth() {
        // budget month Nov 27 to Dec 30: the Nov 27 one, and not also Dec 27 (its earliest unpaid in the month)
        val unpaid = budget(listOf(bill("tel", "2026-11-27")))
        assertEquals(listOf("Sample tel" to "2026-11-27"), dues(unpaid, "2026-11-27"))
        // paid: the Dec 27 one
        val b = budget(listOf(bill("tel", "2026-11-27")), listOf(paid("tel", "2026-11-27")))
        assertEquals(listOf("Sample tel" to "2026-12-27"), dues(b, "2026-11-27"))
        // the next month (Dec 30 to Jan 29) has its own: Jan 27 (Dec 27 is this month's, behind the unpaid Nov 27)
        assertEquals(listOf("Sample tel" to "2027-01-27"), dues(unpaid, "2026-11-27", offset = 1))
    }

    @Test fun aBillDueOnTheNextPaydayIsNextMonths() {
        val b = budget(listOf(bill("rent", "2026-12-30"), bill("gym", "2026-12-29")))
        assertEquals(listOf("Sample gym" to "2026-12-29"), dues(b, "2026-11-27"))
        assertEquals(listOf("Sample rent" to "2026-12-30"), dues(b, "2026-11-27", offset = 1).filter { it.first == "Sample rent" })
    }

    @Test fun anUnpaidBillFromLastBudgetMonthIsListedOverdue() {
        val b = budget(listOf(bill("water", "2026-11-20")))
        assertEquals(listOf("2026-11-20", "2026-12-20"), dues(b, "2026-11-27").map { it.second })
        // only the month that holds today lists what's overdue
        assertTrue(dues(b, "2026-11-27", offset = 1).none { it.second == "2026-11-20" })
    }

    @Test fun markingPaydayMovesTheWindowWithoutASchedule() {
        val b = listOf(bill("tel", "2026-11-27"), bill("gym", "2026-12-15"))
        // every two weeks, Oct 30 and Nov 13 marked: today Nov 14 is in Nov 13 to Nov 27
        assertEquals(emptyList<Pair<String, String>>(), dues(budget(b, paydays = listOf("2026-10-30", "2026-11-13"), schedule = null), "2026-11-14"))
        // Nov 27 marked: Nov 27 to Dec 11
        assertEquals(listOf("2026-11-27"), dues(budget(b, paydays = listOf("2026-10-30", "2026-11-13", "2026-11-27"), schedule = null), "2026-11-27").map { it.second })
    }

    @Test fun noPaydayAtAllIsCalendarMonths() {
        val b = budget(listOf(bill("tel", "2026-11-27"), bill("gym", "2026-12-15")), paydays = emptyList(), schedule = null)
        assertEquals(listOf("2026-11-27"), dues(b, "2026-11-14").map { it.second })
        assertEquals(listOf("2026-12-15", "2026-12-27"), dues(b, "2026-11-14", offset = 1).map { it.second })
    }
}
