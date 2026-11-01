package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.ui.CONFIRM_MAX_WIDTH
import net.darkrelay.deaddropfinance.ui.ConfirmDialog
import net.darkrelay.deaddropfinance.ui.ConfirmHost
import net.darkrelay.deaddropfinance.ui.ConfirmLayer
import net.darkrelay.deaddropfinance.ui.ConfirmRed
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.LocalConfirmHost
import net.darkrelay.deaddropfinance.ui.LocalScrollbars
import net.darkrelay.deaddropfinance.ui.PhonePalette
import net.darkrelay.deaddropfinance.ui.ProgramPalette
import net.darkrelay.deaddropfinance.ui.ScrollColumn
import net.darkrelay.deaddropfinance.ui.ScrollRow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.15.6: the one confirm (a card like the program's over a scrim on the whole window, Cancel quiet on the left, the
 * action filled on the right, Esc and Enter, focus on Cancel, as wide as its text up to a maximum) and the program's
 * scrollbar (shown while a screen overflows, never on top of its content).
 */
@OptIn(ExperimentalTestApi::class)
class ProgramConfirmAndScrollbarTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.6" }?.let { File(it).also { d -> d.mkdirs() } }

    private fun ComposeUiTest.image(): BufferedImage {
        val roots = onAllNodes(isRoot())
        val img = roots[0].captureToImage().toAwtImage()
        for (k in 1 until roots.fetchSemanticsNodes().size) img.createGraphics().apply { drawImage(roots[k].captureToImage().toAwtImage(), 0, 0, null); dispose() }
        return img
    }

    private fun ComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }

    private fun BufferedImage.rgb(x: Float, y: Float) = getRGB(x.toInt(), y.toInt()) and 0xFFFFFF
    private fun argb(c: androidx.compose.ui.graphics.Color) = (c.value shr 32).toInt() and 0xFFFFFF
    private fun dark(rgb: Int) = (rgb shr 16 and 0xFF) < 0x20 && (rgb shr 8 and 0xFF) < 0x20 && (rgb and 0xFF) < 0x20

    /** A confirm on its own, as the program draws it: through the window's host, over a bright page. */
    private fun ComposeUiTest.confirm(text: String, destructive: Boolean, onDone: (String) -> Unit = {}) {
        setContent {
            val host = remember { ConfirmHost() }
            var open by remember { mutableStateOf(true) }
            CompositionLocalProvider(LocalConfirmHost provides host) {
                Box(Modifier.fillMaxSize().background(ProgramPalette.surface).testTag("page"))
                if (open) ConfirmDialog(
                    "Clear this receipt?", text, if (destructive) "Clear" else "Change it",
                    onCancel = { open = false; onDone("cancel") }, onConfirm = { open = false; onDone("action") }, destructive = destructive,
                )
                ConfirmLayer(host)
            }
        }
        waitForIdle()
    }

    @Test fun theCardIsTheProgramsCancelQuietOnTheLeftTheActionFilledOnTheRight() = runDesktopComposeUiTest(1100, 700) {
        confirm("Nothing has been saved. The photos, what was read and your changes are thrown out.", destructive = true)
        val img = image()
        val card = bounds("confirm.card")
        val cancel = bounds("confirm.cancel")
        val action = bounds("confirm.action")
        // the card: #11161B inside, a 1px #1F2A33 border, its corner rounded (10dp: the very corner is the scrim's)
        assertEquals(0x11161B, img.rgb(card.left + 6, card.center.y), "fill")
        assertEquals(0x1F2A33, img.rgb(card.left, card.center.y), "border")
        assertEquals(0x1F2A33, img.rgb(card.center.x, card.top), "border")
        val dimmed = img.rgb(2f, 2f)
        assertEquals(dimmed, img.rgb(card.left + 1, card.top + 1), "rounded corner: the scrim shows there")
        // the scrim on the whole window: the page (a card's color) is dimmed right up to the top corner
        assertTrue(dark(dimmed) && dimmed != 0x11161B, Integer.toHexString(dimmed))
        // Cancel left of the action, both inset in the card's padding
        assertTrue(cancel.right <= action.left)
        assertTrue(action.right <= card.right - 16 && cancel.left >= card.left + 16 && action.bottom <= card.bottom - 16)
        // Cancel quiet (the card shows through it), the action filled red
        assertEquals(0x11161B, img.rgb(cancel.left + 4, cancel.center.y), "Cancel isn't filled")
        assertEquals(argb(ConfirmRed), img.rgb(action.left + 4, action.center.y), "Clear is filled red")
        // focus starts on Cancel
        onNodeWithTag("confirm.cancel", useUnmergedTree = true).assertIsFocused()
        shots?.let { javax.imageio.ImageIO.write(img, "png", File(it, "confirm-alone-1100x700.png")) }
    }

    @Test fun anActionThatKeepsWorkIsFilledTeal() = runDesktopComposeUiTest(1100, 700) {
        confirm("Envelope balances and Ready to assign will be recalculated from this date.", destructive = false)
        val action = bounds("confirm.action")
        assertEquals(argb(ProgramPalette.teal), image().rgb(action.left + 4, action.center.y))
    }

    /** 0.15.7: Esc cancels; Enter presses the focused button: Cancel when it opens, the action once it has the focus. */
    @Test fun escCancelsAndEnterPressesTheFocusedButton() {
        runDesktopComposeUiTest(1100, 700) {
            var got = ""
            confirm("Nothing has been saved.", destructive = true) { got = it }
            onNodeWithTag("confirm.cancel", useUnmergedTree = true).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertEquals("cancel", got)
            assertTrue(onAllNodesWithTag("confirm.card", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
        runDesktopComposeUiTest(1100, 700) {
            var got = ""
            confirm("Nothing has been saved.", destructive = true) { got = it }
            // focus starts on Cancel: Enter alone cancels
            onNodeWithTag("confirm.cancel", useUnmergedTree = true).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("cancel", got)
        }
        runDesktopComposeUiTest(1100, 700) {
            var got = ""
            confirm("Nothing has been saved.", destructive = true) { got = it }
            // Tab to the action, then Enter presses it
            onNodeWithTag("confirm.cancel", useUnmergedTree = true).performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onNodeWithTag("confirm.action", useUnmergedTree = true).assertIsFocused()
            onNodeWithTag("confirm.action", useUnmergedTree = true).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("action", got)
        }
    }

    @Test fun theCardFitsShortTextAndWrapsLongTextWithinItsMaximum() {
        runDesktopComposeUiTest(1400, 900) {
            confirm("Gone.", destructive = true)
            val card = bounds("confirm.card")
            assertTrue(card.width < CONFIRM_MAX_WIDTH.value, "short text: narrower than the maximum (${card.width})")
        }
        runDesktopComposeUiTest(1400, 900) {
            confirm("This deletes the account and its 140 transactions for good. Envelope balances and Ready to assign change to match. A transfer with another account stays there as an entry to sort. This can't be undone.", destructive = true)
            val card = bounds("confirm.card")
            val text = bounds("confirm.text")
            assertEquals(CONFIRM_MAX_WIDTH.value, card.width, 0.5f)
            assertTrue(text.height > 19 * 2, "wrapped onto lines (${text.height})")
            assertTrue(text.right <= card.right - 16)
        }
    }

    // ---- the program's screens ----

    private fun ComposeUiTest.program(w: Int, h: Int): AppModel {
        val m = SampleBudget.filled(tmp.newFolder("DeadDropFinance")).model.also { models += it }
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        return m
    }

    @Test fun theProgramsOwnConfirmCoversTheWholeWindow() = runDesktopComposeUiTest(1400, 900) {
        program(1400, 900)
        onNodeWithTag("nav.ACCOUNTS", useUnmergedTree = true).performClick(); waitForIdle()
        fun click(t: String) { runCatching { onNodeWithTag(t, useUnmergedTree = true).performScrollTo() }; onNodeWithTag(t, useUnmergedTree = true).performClick(); waitForIdle() }
        click("editAccount.Cash")
        click("account.delete")
        onNodeWithTag("dialog.deleteAccount", useUnmergedTree = true).assertExists()
        onNodeWithTag("dialog.cancel", useUnmergedTree = true).assertIsFocused()
        val img = image()
        // the menu strip at the very top is under the scrim too
        assertTrue(dark(img.rgb(2f, 3f)), Integer.toHexString(img.rgb(2f, 3f)))
        val action = bounds("dialog.confirm")
        assertEquals(argb(ConfirmRed), img.rgb(action.left + 4, action.center.y), "Delete for good is filled red")
        assertTrue(bounds("dialog.cancel").right <= action.left)
        shots?.let { javax.imageio.ImageIO.write(img, "png", File(it, "program-delete-account-confirm-1400x900.png")) }
        onNodeWithTag("dialog.cancel", useUnmergedTree = true).performClick(); waitForIdle()
        assertTrue(onAllNodesWithTag("dialog.deleteAccount", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aShortScreenHasNoScrollbar() = runDesktopComposeUiTest(1400, 900) {
        val m = program(1400, 900)
        m.go(Screen.SYNC); waitForIdle()
        assertTrue(onAllNodesWithTag("scrollbar.vertical", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        shots?.let { javax.imageio.ImageIO.write(image(), "png", File(it, "program-short-screen-no-scrollbar-1400x900.png")) }
    }

    @Test fun aLongScreenShowsItOnTheRightAndNothingIsUnderIt() = runDesktopComposeUiTest(1100, 700) {
        val m = program(1100, 700)
        m.go(Screen.SETTINGS); waitForIdle()
        val bar = bounds("scrollbar.vertical")
        assertTrue(bar.width <= ProgramScrollbars.gutter.value + 0.5f && bar.width > 0)
        // every piece of the screen's content ends left of the bar
        val content = onAllNodes(androidx.compose.ui.test.hasTestTag("budgetStart.change"), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        assertTrue(content.right <= bar.left, "content ${content.right} vs bar ${bar.left}")
    }

    @Test fun theSharedContainersShowTheBarOnlyWhenTheyOverflow() = runDesktopComposeUiTest(600, 400) {
        var rows by mutableStateOf(3)
        setContent {
            CompositionLocalProvider(LocalScrollbars provides ProgramScrollbars) {
                androidx.compose.foundation.layout.Column {
                    ScrollColumn(Modifier.fillMaxWidth().height(300.dp)) {
                        repeat(rows) { Text("Row $it", Modifier.fillMaxWidth().height(40.dp).testTag("row.$it")) }
                    }
                    ScrollRow(Modifier.fillMaxWidth()) {
                        repeat(rows) { Text("Wide $it", Modifier.width(150.dp).height(30.dp).testTag("wide.$it")) }
                    }
                }
            }
        }
        waitForIdle()
        assertTrue(onAllNodesWithTag("scrollbar.vertical", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "3 rows fit")
        assertTrue(onAllNodesWithTag("scrollbar.horizontal", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "3 fit across")
        rows = 30
        waitForIdle()
        val bar = bounds("scrollbar.vertical")
        assertTrue(bounds("row.0").right <= bar.left, "the rows end left of the bar")
        assertTrue(bar.right <= 600f)
        val across = bounds("scrollbar.horizontal")
        assertTrue(bounds("wide.0").bottom <= across.top, "the row's content ends above its bar")
        assertEquals(0x1F2A33, ProgramScrollbars.rest.value.shr(32).toInt() and 0xFFFFFF)
    }
}
