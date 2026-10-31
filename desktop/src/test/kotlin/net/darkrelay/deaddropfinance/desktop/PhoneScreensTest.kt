package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.13.0: the phone app's screens in the program (PhoneScreens): each sidebar item and each button that opens one,
 * on an invented budget in a temp folder.
 */
class PhoneScreensTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var made: SampleBudget.Made
    private val m get() = made.model
    private fun id(name: String) = made.ids.getValue(name)

    private fun show() {
        made = SampleBudget.filled(tmp.newFolder("DeadDropFinance"))
        compose.setContent { DesktopApp(m, Shell(run = { block -> runBlocking { block() } }, settings = Settings(tmp.newFolder("settings")))) }
        compose.waitForIdle()
    }

    @After fun close() { runCatching { compose.setContent {}; compose.waitForIdle() }; if (::made.isInitialized) m.engine?.close() }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun click(t: String) { tag(t).performClick(); compose.waitForIdle() }
    private fun text(t: String) = compose.onNodeWithText(t, substring = true, useUnmergedTree = true)
    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun until(t: String) = compose.waitUntil(5_000) { shows(t) }

    /** 0.14.0: one sidebar, in this order; Home and More are gone, their parts on Budget, Receipts and Settings. */
    @Test fun theSidebarHasExactlyTheNineItemsAndEachShowsItsScreen() {
        show()
        val navs = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("nav item") { n ->
            n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith("nav.") == true
        }, useUnmergedTree = true).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }.map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
        assertEquals(listOf("BUDGET", "ACTIVITY", "ACCOUNTS", "RECEIPTS", "BILLS", "CATEGORIES", "REPORTS", "SYNC", "SETTINGS").map { "nav.$it" }, navs)
        tag("nav.HOME").assertDoesNotExist()
        tag("nav.MORE").assertDoesNotExist()
        // Receipts opens from the sidebar: the phone's list, its name only in the top bar (no heading or Back of its own)
        click("nav.RECEIPTS")
        until("Scan")
        assertEquals(2, compose.onAllNodes(androidx.compose.ui.test.hasText("Receipts"), useUnmergedTree = true).fetchSemanticsNodes().size, "the sidebar and the top bar")
        assertTrue(!shows("Back"))
        click("nav.BILLS")
        tag("card.nextPayday").assertExists()
        click("nav.CATEGORIES")
        tag("card.categoryTotal").assertExists()
        // what More held: in Settings, as their own sections
        click("nav.SETTINGS")
        for (t in listOf("settings.rules", "setting.autoApprove", "settings.importCsv", "settings.importSetup", "setting.alertBills", "setting.alertAutopay",
            "settings.setup", "settings.purge", "settings.erase", "settings.about", "setting.hideScreen")) tag(t).assertExists()
        // the program's own: no app lock, no phone updates, nothing said twice
        assertTrue(!shows("App lock"))
        assertTrue(!shows("Check for updates at startup") || compose.onAllNodes(androidx.compose.ui.test.hasText("Check for updates at startup"), useUnmergedTree = true).fetchSemanticsNodes().size == 1)
        assertEquals(1, compose.onAllNodes(androidx.compose.ui.test.hasText("Budget start date"), useUnmergedTree = true).fetchSemanticsNodes().size, "budget start is in Settings once")
        assertEquals(1, compose.onAllNodes(androidx.compose.ui.test.hasText("Currency"), useUnmergedTree = true).fetchSemanticsNodes().size, "currency is in Settings once")
    }

    @Test fun programScreensOpenThePhonesAndBackReturns() {
        show()
        click("nav.ACTIVITY")
        click("toSort")
        until("To sort")
        assertTrue(m.phoneNav != null)
        click("nav.BUDGET")
        assertNull(m.phoneNav)
        click("fillOrder")
        until("drag the handle")
        click("nav.ACTIVITY")
        click("act.Corner Market")
        click("dialog.details")
        until("Corner Market")
        text("Back").performClick()
        compose.waitForIdle()
        assertNull(m.phoneNav, "Back on the first phone screen returns to the program's")
        m.drawerEnvelope = id("Groceries")
        compose.waitForIdle()
        click("drawer.details")
        until("Groceries")
    }
}
