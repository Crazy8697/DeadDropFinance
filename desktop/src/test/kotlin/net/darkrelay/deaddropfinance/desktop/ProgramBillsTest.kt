package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.13.0: the program's Bills (the phone's tab): adding a bill, its detail and Mark paid, editing and deleting it
 * after the phone's question; Subscriptions found from repeating charges, made into a bill; the balance forecast.
 */
class ProgramBillsTest : ProgramScreensBase() {

    private fun last(text: String) {
        val all = compose.onAllNodes(hasText(text), useUnmergedTree = true)
        all[all.fetchSemanticsNodes().size - 1].performClick()
        compose.waitForIdle()
    }

    private fun bills() = runBlocking { store().dao.billsNow() }.filter { !it.deleted }

    @Test fun aBillIsAddedPaidEditedAndDeleted() {
        show()
        click("nav.BILLS")
        until("Reminders are off unless you turn them on for a bill.")
        clickText("Add", exact = true)
        until("Name")
        typeInto("Name", "Sample Water")
        typeInto("Amount", "35.00")
        typeInto("Next due (yyyy-mm-dd)", today.plusDays(5).toString())
        clickText("Save", exact = true)
        compose.waitUntil(5_000) { bills().any { it.name == "Sample Water" && it.amountDue == 35_00L } }
        // its detail: Mark paid records the payment
        until("Sample Water")
        clickText("Sample Water", exact = true)
        until("Mark paid")
        clickText("Mark paid", exact = true)
        compose.waitUntil(5_000) { runBlocking { store().dao.billPaymentsNow() }.any { p -> !p.deleted && p.billId == bills().single().id } }
        // edited: a new amount (the phone screen's Edit, not the program's Edit menu)
        last("Edit")
        until("Delete bill")
        typeInto("Amount", "40.00")
        clickText("Save", exact = true)
        compose.waitUntil(5_000) { bills().single().amountDue == 40_00L }
        // deleted after the phone's question
        until("Mark paid")
        last("Edit")
        clickText("Delete bill")
        until("Delete this bill?")
        assertTrue(shows("The bill and its payment records go. The transactions themselves stay."))
        last("Delete")
        compose.waitUntil(5_000) { bills().isEmpty() }
    }

    @Test fun repeatingChargesShowUnderSubscriptionsAndOneBecomesABill() {
        show {
            for (d in listOf(61L, 31L, 1L)) saveTransaction(null, TransactionDraft(ids.getValue("Checking"), today.minusDays(d), "Sample Streamer", "", -12_99,
                listOf(LineDraft(ids.getValue("Gifts"), -12_99, categoryId = category("Other")))))
        }
        click("nav.BILLS")
        until("1 repeating charge")
        clickText("1 repeating charge")
        until("Sample Streamer")
        clickText("Sample Streamer")
        until("Make it a bill")
        // the button (the section has the same title)
        last("Make it a bill")
        // made at once, and its screen opens
        until("Made a bill. Its payments are matched from now on.")
        compose.waitUntil(5_000) { bills().any { it.amountDue == 12_99L } }
    }

    @Test fun theForecastCardOpensTheBalanceForecastWithPlanAndUsualPace() {
        show()
        click("nav.HOME")
        until("Balance forecast to payday")
        clickText("Balance forecast to payday")
        until("Balance forecast")
        until("Usual pace")
        assertTrue(shows("Plan"))
        clickText("Back", exact = true)
        until("Safe to spend")
    }

    @Test fun aBillDueBeforePaydayIsInBillsWithItsDueDate() {
        show { BillActions(store(), this) { today }.saveBill(null, BillActions.BillDraft("Sample Phone Plan", 45_00, today.plusDays(3))) }
        click("nav.BILLS")
        until("Sample Phone Plan")
        assertTrue(shows("due "))
        assertEquals(1, bills().size)
    }
}
