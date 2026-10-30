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

    @After fun close() { if (::made.isInitialized) m.engine?.close() }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun click(t: String) { tag(t).performClick(); compose.waitForIdle() }
    private fun text(t: String) = compose.onNodeWithText(t, substring = true, useUnmergedTree = true)
    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun until(t: String) = compose.waitUntil(5_000) { shows(t) }

    @Test fun eachSidebarItemShowsThePhonesScreen() {
        show()
        click("nav.HOME")
        until("Safe to spend")
        click("nav.BILLS")
        until("Bills")
        click("nav.CATEGORIES")
        until("Categories")
        click("nav.MORE")
        until("Import CSV")
        // the program's own: no app lock, no phone updates, no phone backup or server rows
        assertTrue(!shows("App lock"))
        assertTrue(!shows("Check for updates at startup"))
        assertTrue(!shows("Backup and restore"))
        assertTrue(shows("from this computer"))
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
