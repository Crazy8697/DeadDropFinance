package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.backup.RecoveryKey
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.Nav
import net.darkrelay.deaddropfinance.ui.PhonePalette
import net.darkrelay.deaddropfinance.ui.ProgramPalette
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.15.7: every form dialog of the program in the shared dialog's frame: a card like the program's (radius 10, #11161B,
 * a 1px #1F2A33 border) over a scrim on the whole window, Cancel quiet, the action filled teal, Esc closes it. Their own
 * width and fields stay; what they do is tested where it always was.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramFormDialogsTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.7" }?.let { File(it).also { d -> d.mkdirs() } }

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

    private fun ComposeUiTest.program(): AppModel {
        val m = SampleBudget.filled(tmp.newFolder()).model.also { models += it }
        m.recoveryKeys = net.darkrelay.deaddropfinance.backup.RecoveryKeys(net.darkrelay.deaddropfinance.backup.MemoryRecoveryKeyStore()).also { it.ensure() }
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        return m
    }

    /** The frame, the scrim and the buttons, as the shared dialog draws them. */
    private fun ComposeUiTest.assertFrame(tag: String, cancel: String?, action: String?) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val img = image()
        val card = bounds(tag)
        assertEquals(0x11161B, img.rgb(card.left + 6, card.top + 6), "$tag: fill")
        // the 1px border, on whichever pixel the card's edge lands
        assertTrue((-1..1).any { img.rgb(card.left + it, card.center.y + 40) == 0x1F2A33 }, "$tag: border")
        assertTrue((-1..1).any { img.rgb(card.center.x, card.top + it) == 0x1F2A33 }, "$tag: border")
        val corner = img.rgb(card.left + 1, card.top + 1)
        assertTrue(dark(corner) && corner != 0x11161B && corner != 0x1F2A33, "$tag: rounded corner, the dimmed window shows there")
        assertTrue(dark(img.rgb(2f, 3f)), "$tag: the scrim covers the menu strip: ${Integer.toHexString(img.rgb(2f, 3f))}")
        if (cancel != null) {
            val c = bounds(cancel)
            assertEquals(0x11161B, img.rgb(c.left + 4, c.center.y), "$tag: $cancel is quiet")
            assertTrue(c.right <= card.right - 16 && c.bottom <= card.bottom - 16, "$tag: inset")
        }
        if (action != null) {
            val a = bounds(action)
            assertEquals(argb(ProgramPalette.teal), img.rgb(a.left + 4, a.center.y), "$tag: $action is filled teal")
            if (cancel != null) assertTrue(bounds(cancel).right <= a.left, "$tag: Cancel left of $action")
        }
    }

    private fun ComposeUiTest.shot(m: AppModel, name: String, w: Int, h: Int) = shots?.let { javax.imageio.ImageIO.write(image(), "png", File(it, "$name-${w}x$h.png")) }

    @Test fun everyFormDialogIsInTheSharedFrame() = runDesktopComposeUiTest(1400, 900) {
        val m = program()
        val ok = m.engine!!.opened as DesktopDatabase.Opened.Ok
        val other = File(tmp.root, "other.ddfbak").also { f -> runBlocking { f.outputStream().use { net.darkrelay.deaddropfinance.backup.DesktopBackups(ok.store.db, ok.file, "t").write(it, RecoveryKey.generate()) } } }
        val forms = listOf(
            Dialog.AddTransaction() to "dialog.add",
            Dialog.MoveMoney() to "dialog.move",
            Dialog.EnvelopeForm() to "dialog.envelope",
            Dialog.AccountForm() to "dialog.account",
            Dialog.Transfer() to "dialog.transfer",
            Dialog.Bonus() to "dialog.bonus",
            Dialog.BudgetStart to "dialog.budgetStart",
            Dialog.NextPayday to "dialog.payday",
            Dialog.Backup to "dialog.backup",
            Dialog.Restore(other) to "dialog.restore",
        )
        for ((d, tag) in forms) {
            m.dialog = d
            waitForIdle()
            // Restore's Next waits for a key (dimmed until then, as before)
            if (d is Dialog.Restore) { waitForIdle(); onNodeWithTag("f.key", useUnmergedTree = true).performTextInput("AAAA"); waitForIdle() }
            assertFrame(tag, "dialog.cancel", "dialog.save")
            m.dialog = null
            waitForIdle()
        }
        // Currency: only Close, quiet
        m.dialog = Dialog.CurrencyPicker; waitForIdle()
        assertFrame("dialog.currency", "dialog.cancel", null)
        m.dialog = null; waitForIdle()
        // the recovery key: Copy quiet, I saved it filled
        m.dialog = Dialog.ShowRecoveryKey(); waitForIdle()
        assertFrame("dialog.recoveryKey", null, "key.saved")
        m.dialog = null; waitForIdle()
    }

    @Test fun escClosesAFormAndEnterInAFieldDoesWhatItDid() = runDesktopComposeUiTest(1400, 900) {
        val m = program()
        m.dialog = Dialog.AddTransaction(); waitForIdle()
        onNodeWithTag("dialog.add", useUnmergedTree = true).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(m.dialog)
        // the card has the focus when it opens (so Esc works); typing still goes to a field once clicked
        m.dialog = Dialog.BudgetStart; waitForIdle()
        onNodeWithTag("f.date", useUnmergedTree = true).performClick(); waitForIdle()
        onNodeWithTag("f.date", useUnmergedTree = true).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(Dialog.BudgetStart, m.dialog, "Enter in a field doesn't close or save the form")
        onNodeWithTag("f.date", useUnmergedTree = true).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(m.dialog, "Esc in a field closes it")
    }

    @Test fun renameCategoryFromTheAppsScreenIsInTheSharedFrame() = runDesktopComposeUiTest(1400, 900) {
        val m = program()
        // the key saved, so the app's screens don't show it over everything first
        m.recoveryKeys?.confirmSaved()
        m.go(Screen.CATEGORIES)
        m.openPhone(Nav.ManageCategories)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Rename", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onAllNodesWithText("Rename", useUnmergedTree = true)[0].performScrollTo().performClick(); waitForIdle()
        assertFrame("dialog.renameCategory", null, null)
        val img = image()
        val save = onAllNodesWithText("Save", useUnmergedTree = true).fetchSemanticsNodes().last().boundsInRoot
        val cancel = onAllNodesWithText("Cancel", useUnmergedTree = true).fetchSemanticsNodes().last().boundsInRoot
        assertEquals(argb(ProgramPalette.teal), img.rgb(save.left - 8, save.center.y), "Save is filled teal")
        assertTrue(cancel.right <= save.left)
    }

    @Test fun screenshotsAtBothSizes() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            val m = program()
            val ok = m.engine!!.opened as DesktopDatabase.Opened.Ok
            val other = File(tmp.root, "other-$w.ddfbak").also { f -> runBlocking { f.outputStream().use { net.darkrelay.deaddropfinance.backup.DesktopBackups(ok.store.db, ok.file, "t").write(it, RecoveryKey.generate()) } } }
            for ((d, name) in listOf(Dialog.AddTransaction() to "program-add-transaction", Dialog.MoveMoney() to "program-move-money", Dialog.Restore(other) to "program-restore")) {
                m.dialog = d
                waitForIdle()
                assertTrue(onAllNodesWithTag("dialog.scrim", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                shot(m, name, w, h)
                m.dialog = null
                waitForIdle()
            }
        }
    }
}
