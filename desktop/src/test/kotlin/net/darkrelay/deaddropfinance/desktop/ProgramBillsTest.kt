package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The program's Bills (0.14.0 in the program's layout, the phone's data and actions): adding a bill, its detail and
 * Mark paid, editing and deleting it after the phone's question; Subscriptions found from repeating charges, made into
 * a bill; the next payday changed by hand; the balance forecast, now on Budget.
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
        click("bills.add")
        until("Name")
        typeInto("Name", "Sample Water")
        typeInto("Amount", "35.00")
        typeInto("Next due (yyyy-mm-dd)", today.plusDays(5).toString())
        clickText("Save", exact = true)
        compose.waitUntil(5_000) { bills().any { it.name == "Sample Water" && it.amountDue == 35_00L } }
        // its detail: Mark paid records the payment
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("bill.Sample Water"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // this cycle and the next are both within 45 days: the first row
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("bill.Sample Water"), useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
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
        click("bills.subscriptions")
        until("Sample Streamer")
        clickText("Sample Streamer")
        until("Make it a bill")
        // the button (the section has the same title)
        last("Make it a bill")
        // made at once, and its screen opens
        until("Made a bill. Its payments are matched from now on.")
        compose.waitUntil(5_000) { bills().any { it.amountDue == 12_99L } }
    }

    @Test fun theForecastOnBudgetOpensTheBalanceForecastWithPlanAndUsualPace() {
        show()
        click("nav.BUDGET")
        until("Balance forecast to payday")
        click("forecast.details")
        until("Usual pace")
        assertTrue(shows("Plan"))
        clickText("Back", exact = true)
        until("Balance forecast to payday")
    }

    @Test fun billsIsLaidOutInTheProgramsStyleWithItsNameOnlyInTheTopBar() {
        show { BillActions(store(), this) { today }.saveBill(null, BillActions.BillDraft("Sample Gym", 30_00, today.plusDays(4), autopay = true)) }
        click("nav.BILLS")
        until("Sample Gym")
        // the program's cards and table, not the phone's column: no heading of its own, the month stepper in the top bar
        tag("card.nextPayday").assertExists()
        tag("card.comingUp").assertExists()
        // a monthly bill: this cycle and the next, each a row
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("bill.Sample Gym"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        tag("month.label").assertExists()
        assertEquals(2, compose.onAllNodes(androidx.compose.ui.test.hasText("Bills"), useUnmergedTree = true).fetchSemanticsNodes().size, "the sidebar and the top bar")
        assertTrue(shows("autopay"))
        assertEquals(money(30_00), compose.onAllNodes(androidx.compose.ui.test.hasTestTag("billAmount.Sample Gym"), useUnmergedTree = true)[0].fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text })
    }

    @Test fun theNextPaydayIsChangedByHandAndBackToThePrediction() {
        show()
        click("nav.BILLS")
        until("Guessed from one payday")
        click("bills.payday")
        until("When you expect the deposit.")
        tag("f.payday").performTextClearance(); tag("f.payday").performTextInput(today.plusDays(9).toString()); compose.waitForIdle()
        click("dialog.save")
        until("Set by you")
        assertEquals(today.plusDays(9), runBlocking { BillActions(store(), a()) { today }.paydayCorrection() })
        click("bills.payday")
        click("payday.predicted")
        compose.waitUntil(5_000) { runBlocking { BillActions(store(), a()) { today }.paydayCorrection() } == null }
    }

    @Test fun aBillDueBeforePaydayIsInBillsWithItsDueDate() {
        show { BillActions(store(), this) { today }.saveBill(null, BillActions.BillDraft("Sample Phone Plan", 45_00, today.plusDays(3))) }
        click("nav.BILLS")
        until("Sample Phone Plan")
        assertTrue(shows("due "))
        assertEquals(1, bills().size)
    }
}
