package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.RuleOrigin
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.domain.Arrivals
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.Rules
import net.darkrelay.deaddropfinance.domain.Sorting
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.13.0, sorting in the program (the phone's screens): To sort and Approve, the Rules screen (list, edit, order,
 * delete, auto-approve), Recently sorted, and the card for a rule learned from repeated choices.
 */
class ProgramSortingTest : ProgramScreensBase() {

    // what arrives from the bank, written as the phone's SortingTest writes it: To sort, from CSV, no category yet
    @Suppress("UnusedReceiverParameter")
    private suspend fun BudgetActions.toSort(payee: String, cents: Long): String = store().write {
        val t = put(null, net.darkrelay.deaddropfinance.data.TransactionEntity(accountId = ids.getValue("Checking"), date = today.minusDays(1), payee = payee, amount = -cents,
            importDescription = payee, source = net.darkrelay.deaddropfinance.data.TransactionSource.CSV, createdAt = now, updatedAt = now))
        put(null, net.darkrelay.deaddropfinance.data.TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = -cents, createdAt = now, updatedAt = now))
        t.id
    }

    private fun rule(text: String, envelope: String) = runBlocking {
        Sorting(store(), a()).saveRule(null, Sorting.RuleDraft(text, splits = listOf(Rules.Split(id(envelope), 1)), categoryId = category("Groceries")))
    }

    @Test fun toSortShowsARulesSuggestionAndApproveSortsIt() {
        lateinit var tx: String
        show { tx = toSort("Sample Bookshop 12", 20_00) }
        rule("sample bookshop", "Gifts")
        click("nav.ACTIVITY")
        click("toSort")
        until("Sample Bookshop 12")
        until("Approve all suggested (1)")
        clickText("Approve", exact = true)
        compose.waitUntil(5_000) { refresh(); b().toSort.none { it.id == tx } }
        val line = b().lines.single { it.transactionId == tx }
        assertEquals(LineBucket.ENVELOPE, line.bucket)
        assertEquals(id("Gifts"), line.envelopeId)
    }

    @Test fun theRulesScreenListsEditsOrdersAndDeletesRules() {
        show()
        rule("sample bookshop", "Gifts")
        rule("sample hardware", "Groceries")
        click("nav.MORE")
        clickText("Rules", exact = true)
        until("\"sample bookshop\"")
        until("\"sample hardware\"")
        // a new rule is tried first; Up on the second moves it back to the top
        val before = runBlocking { store().dao.rulesNow() }.sortedBy { it.priority }.map { it.matchText }
        assertEquals(listOf("sample hardware", "sample bookshop"), before)
        compose.onAllNodes(hasText("Up"), useUnmergedTree = true)[1].performClick()
        compose.waitUntil(5_000) { runBlocking { store().dao.rulesNow() }.sortedBy { it.priority }.first().matchText == "sample bookshop" }
        // edit one: its payee text changes
        clickText("\"sample bookshop\"")
        until("Payee has")
        typeInto("Payee has", "sample books")
        clickText("Save", exact = true)
        compose.waitUntil(5_000) { runBlocking { store().dao.rulesNow() }.any { it.matchText == "sample books" } }
        // delete it, after the phone's question
        until("\"sample books\"")
        clickText("\"sample books\"")
        until("Payee has")
        clickText("Delete rule", exact = true)
        until("Delete this rule?")
        assertTrue(shows("New transactions from this payee won't get its suggestion any more."))
        val deletes = compose.onAllNodes(hasText("Delete"), useUnmergedTree = true)
        deletes[deletes.fetchSemanticsNodes().size - 1].performClick()
        compose.waitUntil(5_000) { runBlocking { store().dao.rulesNow() }.none { it.matchText == "sample books" && !it.deleted } }
    }

    @Test fun autoApproveSortsRuleMatchesAsTheyArriveAndListsThemUnderRecentlySorted() {
        show()
        rule("sample bookshop", "Gifts")
        click("nav.MORE")
        until("Approve rule matches automatically")
        toggleFor("Approve rule matches automatically")
        assertTrue(ProgramAppSettings(settings).autoApprove, "the setting is kept in the program's settings")
        val tx = runBlocking {
            val id = a().toSort("Sample Bookshop 7", 15_00)
            Arrivals(store(), a(), { today }).landed(listOf(id), autoApprove = ProgramAppSettings(settings).autoApprove)
            id
        }
        refresh()
        assertEquals(SortedBy.AUTO, b().transactions.first { it.id == tx }.sortedBy)
        click("nav.ACTIVITY")
        click("toSort")
        until("Recently sorted by rules")
        until("Sample Bookshop 7")
        assertTrue(shows("Click one to change it."))
    }

    @Test fun aRuleLearnedFromRepeatedChoicesShowsItsCardWithUndoAndOk() {
        show()
        runBlocking {
            // three choices by hand for one payee, as the To sort row makes them
            val plain = BudgetActions(store())
            val s = Sorting(store(), plain)
            repeat(Sorting.LEARN_AFTER) { i ->
                val t = plain.toSort("Sample Taco Stand", 9_00L + i)
                s.sortByHand(t, id("Groceries"))
            }
        }
        refresh()
        val learned = runBlocking { store().dao.rulesNow() }.filter { it.origin == RuleOrigin.LEARNED }
        assertEquals(1, learned.size, "the third choice for the same payee made a rule")
        click("nav.ACTIVITY")
        click("toSort")
        until("New rule:")
        assertTrue(shows("Made from your last ${Sorting.LEARN_AFTER} choices for this payee."))
        clickText("OK", exact = true)
        compose.waitUntil(5_000) { runBlocking { store().dao.rulesNow() }.first { it.id == learned.single().id }.acknowledged }
        gone("New rule:")
    }
}
