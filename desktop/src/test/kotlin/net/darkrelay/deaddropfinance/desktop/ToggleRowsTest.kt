package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertTrue

/**
 * 0.15.0: no toggle row's text runs into its switch (Settings' Rules card had "Approve rule matches automatically"
 * against it). Every toggle row in the program, Settings' and the envelope and account dialogs', keeps its text and
 * switch apart by the gap, at the size the window opens (1400 x 900) and at its minimum (1100 x 700).
 */
@OptIn(ExperimentalTestApi::class)
class ToggleRowsTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() } }

    private val settingsToggles = listOf("setting.checkUpdates", "setting.hideScreen", "setting.autoApprove", "setting.alertBills", "setting.alertAutopay")

    private fun SemanticsNodeInteractionsProvider.bounds(tag: String): Rect = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun SemanticsNodeInteractionsProvider.apart(tag: String, where: String) {
        val text = bounds("$tag.text")
        val switch = bounds(tag)
        val gap = switch.left - text.right
        assertTrue(gap >= TOGGLE_GAP.value - 0.5f, "$where: $tag's text ends ${-gap} px into its switch (text $text, switch $switch)")
        assertTrue(text.width > 0 && switch.width > 0, "$where: $tag is drawn")
    }

    private fun check(w: Int, h: Int) = runDesktopComposeUiTest(w, h) {
        val made = SampleBudget.filled(tmp.newFolder("DeadDropFinance"))
        val m = made.model.also { models += it }
        m.go(Screen.SETTINGS)
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        for (t in settingsToggles) {
            onNodeWithTag(t, useUnmergedTree = true).performScrollTo()
            waitForIdle()
            apart(t, "Settings at $w x $h")
        }
        m.dialog = Dialog.EnvelopeForm(made.ids["Groceries"])
        waitForIdle()
        apart("f.everyday", "the envelope dialog at $w x $h")
        m.dialog = Dialog.AccountForm(made.ids["Checking"])
        waitForIdle()
        apart("f.trackOnly", "the account dialog at $w x $h")
        apart("f.show", "the account dialog at $w x $h")
    }

    @Test fun textAndSwitchNeverMeetAtTheOpeningSize() = check(1400, 900)

    @Test fun textAndSwitchNeverMeetAtTheMinimumSize() = check(1100, 700)
}
