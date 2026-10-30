package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 0.13.0: a transaction's Details in the program (the phone's screen, from the edit form): each line's category,
 * Make a rule from this, payday marking, a wash and a return. And the Categories tab with Manage categories, with the
 * phone's CategoryScreensTest cases.
 */
class ProgramDetailsTest : ProgramScreensBase() {

    private fun details(payee: String) {
        click("nav.ACTIVITY")
        click("act.$payee")
        click("dialog.details")
        until("Edit")
    }

    /** The phone's Picker under [label]: its button shows [current]; the menu item [option] is the last node with that text. */
    private fun pick(label: String, current: String, option: String) {
        val top = node(label, exact = true).fetchSemanticsNode().boundsInRoot.center.y
        val all = compose.onAllNodes(hasText(current), useUnmergedTree = true)
        val ns = all.fetchSemanticsNodes()
        val i = ns.indices.filter { ns[it].boundsInRoot.center.y > top }.minBy { ns[it].boundsInRoot.center.y }
        all[i].performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(option), useUnmergedTree = true).fetchSemanticsNodes().size >= 2 || shows(option) }
        val items = compose.onAllNodes(hasText(option), useUnmergedTree = true)
        items[items.fetchSemanticsNodes().size - 1].performClick()
        compose.waitForIdle()
    }

    private fun tx(payee: String) = b().transactions.first { it.payee == payee }

    @Test fun eachLinesCategoryChangesWithoutMovingTheMoney() {
        show()
        val t = tx("Corner Market")
        details("Corner Market")
        until("Changing the category doesn't move the money")
        pick("Category", "Groceries", "Snacks")
        compose.waitUntil(5_000) { refresh(); b().lines.single { it.transactionId == t.id }.categoryId == category("Snacks") }
        assertEquals(id("Groceries"), b().lines.single { it.transactionId == t.id }.envelopeId, "the money stays in its envelope")
    }

    @Test fun makeARuleFromThisOpensTheRuleEditorFilledIn() {
        show()
        details("Corner Market")
        clickText("Make a rule from this")
        until("Payee has")
        clickText("Save", exact = true)
        compose.waitUntil(5_000) { runBlocking { store().dao.rulesNow() }.any { it.matchText.contains("corner market") } }
    }

    @Test fun aDepositIsUnmarkedAndMarkedAsPaydayAfterThePhonesQuestion() {
        show()
        val pay = tx("Sample Payroll Co")
        assertTrue(pay.isPayday)
        details("Sample Payroll Co")
        clickText("Unmark payday")
        compose.waitUntil(5_000) { refresh(); !tx("Sample Payroll Co").isPayday }
        clickText("Mark as payday", exact = true)
        until("Mark as payday?")
        val marks = compose.onAllNodes(hasText("Mark as payday"), useUnmergedTree = true)
        marks[marks.fetchSemanticsNodes().size - 1].performClick()
        compose.waitUntil(5_000) { refresh(); tx("Sample Payroll Co").isPayday }
    }

    @Test fun moneyInAndOutOfTheSameAmountPassesThroughAsAWash() {
        show {
            saveTransaction(null, TransactionDraft(ids.getValue("Checking"), today.minusDays(2), "Sample Pass In", "", 25_00, listOf(LineDraft(null, 25_00, toReadyToAssign = true))))
            saveTransaction(null, TransactionDraft(ids.getValue("Checking"), today.minusDays(2), "Sample Pass Out", "", -25_00,
                listOf(LineDraft(ids.getValue("Gifts"), -25_00, categoryId = category("Other")))))
        }
        details("Sample Pass In")
        until("Pass it through (wash) with")
        clickText("Sample Pass Out")
        compose.waitUntil(5_000) { refresh(); tx("Sample Pass In").isWash && tx("Sample Pass Out").isWash }
        until("Pass-through money (a wash)")
        clickText("Not a wash")
        compose.waitUntil(5_000) { refresh(); !tx("Sample Pass In").isWash }
    }

    @Test fun moneyInIsMarkedAsAReturnOfAPurchase() {
        show {
            saveTransaction(null, TransactionDraft(ids.getValue("Checking"), today, "Sample Refund", "", 10_00, listOf(LineDraft(null, 10_00, toReadyToAssign = true))))
        }
        details("Sample Refund")
        clickText("This is a return")
        until("Return of which purchase?")
        clickText("Corner Market, ")
        compose.waitUntil(5_000) { refresh(); tx("Sample Refund").returnOf == tx("Corner Market").id }
        assertTrue(b().lines.filter { it.transactionId == tx("Sample Refund").id }.all { it.bucket == LineBucket.ENVELOPE })
        until("A return isn't income.")
    }

    // ---- Categories (the phone's CategoryScreensTest cases) ----

    @Test fun theCategoriesTabShowsTheTotalAndEachCategoryAndStepsToOlderMonths() {
        show()
        click("nav.CATEGORIES")
        until("Total spent")
        until("Groceries")
        assertTrue(shows("$42.00"))
        clickText("‹ Older")
        until("Newer ›")
        clickText("Newer ›")
        until("$42.00")
        // a category lists its purchases for the month
        clickText("Groceries", exact = true)
        until("Corner Market")
    }

    @Test fun manageCategoriesAddsRenamesAndAsksWhereADeletedCategorysPurchasesGo() {
        show()
        click("nav.CATEGORIES")
        clickText("Manage", exact = true)
        until("New category")
        typeInto("New category", "Sample Hobbies")
        clickText("Add", exact = true)
        compose.waitUntil(5_000) { runBlocking { store().dao.categoriesNow() }.any { it.name == "Sample Hobbies" } }
        // Groceries is used by a purchase: deleting it asks where its purchases go
        val deletes = compose.onAllNodes(hasText("Delete"), useUnmergedTree = true)
        val row = node("Groceries", exact = true).fetchSemanticsNode().boundsInRoot.center.y
        val nodes = deletes.fetchSemanticsNodes()
        deletes[nodes.indices.minBy { kotlin.math.abs(nodes[it].boundsInRoot.center.y - row) }].performClick()
        compose.waitForIdle()
        until("Delete Groceries?")
        until("Choose the category to move them to")
        clickText("Cancel", exact = true)
        assertNotNull(runBlocking { store().dao.categoriesNow() }.firstOrNull { it.name == "Groceries" && !it.deleted })
        assertFalse(shows("Delete Groceries?"))
    }
}
