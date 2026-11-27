package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.PaydayButton
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.PhonePalette
import net.darkrelay.deaddropfinance.ui.ProgramPalette
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dialog text colors (program 0.15.13), on an invented budget: the Payday popup's Clean up label in the palette's text
 * color and the line under the balance in its secondary color (read from the drawn pixels, never black), the disabled
 * Payday button readable, and the program's dialogs drawn for screenshots. At 1400 x 900 and 1100 x 700; screenshots
 * only with -Pddf.screens and -Pddf.screensRound=0.15.13.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramDialogColorsTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.13" }?.let { File(it).also { d -> d.mkdirs() } }
    private val today = LocalDate.of(2026, 11, 20)
    private lateinit var acct: String

    private fun ComposeUiTest.image(): BufferedImage {
        val roots = onAllNodes(isRoot())
        val img = roots[0].captureToImage().toAwtImage()
        for (k in 1 until roots.fetchSemanticsNodes().size) img.createGraphics().apply { drawImage(roots[k].captureToImage().toAwtImage(), 0, 0, null); dispose() }
        return img
    }

    private fun ComposeUiTest.has(t: String) = onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.click(t: String) { onNodeWithTag(t, useUnmergedTree = true).performClick(); waitForIdle() }
    private fun ComposeUiTest.shot(name: String, w: Int, h: Int) { shots?.let { ImageIO.write(image(), "png", File(it, "$name-${w}x$h.png")) } }

    /** The color most of [tag]'s pixels have, other than the dialog card's own. */
    private fun ComposeUiTest.inkOf(tag: String, also: List<androidx.compose.ui.graphics.Color> = emptyList()): Int {
        val r = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val img = image()
        val counts = HashMap<Int, Int>()
        val bgs = (listOf(ProgramPalette.surface) + also).map { it.toArgb() }.toSet()
        for (x in r.left.toInt() until r.right.toInt()) for (y in r.top.toInt() until r.bottom.toInt()) {
            val c = img.getRGB(x, y) or 0xFF000000.toInt()
            if (c !in bgs) counts[c] = (counts[c] ?: 0) + 1
        }
        return counts.maxBy { it.value }.key
    }

    private fun androidx.compose.ui.graphics.Color.toArgb(): Int = (0xFF shl 24) or ((red * 255 + 0.5f).toInt() shl 16) or ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()

    private fun model(): AppModel = runBlocking {
        var tick = LocalDate.of(2026, 10, 1).atTime(9, 0).toEpochSecond(ZoneOffset.UTC) * 1000
        val m = AppModel(Engine.start(tmp.newFolder()) { tick += 60_000; tick }) { today }
        val a = m.actions!!
        acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 1_000_00, LocalDate.of(2026, 9, 1))
        m.store()!!.ensureBudgetStart(LocalDate.of(2026, 9, 1), setupDone = true)
        for ((name, target) in listOf("Phone" to 100_00L, "Fun" to 60_00L)) a.saveEnvelope(null, EnvelopeDraft(name, EnvelopeMode.FILL_TO_TARGET, target = target), LocalDate.of(2026, 10, 1))
        val pay = a.saveTransaction(null, TransactionDraft(acct, LocalDate.of(2026, 10, 28), "Sample Payroll Co", "", 2_000_00, listOf(LineDraft(null, 2_000_00, toReadyToAssign = true))))
        a.markPayday(pay, LocalDate.of(2026, 10, 28), fill = false)
        m.refresh()
        m.also { models += it }
    }

    @Test fun thePaydayPopupIsReadable() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            val m = model()
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
            waitForIdle()
            click("top.payday")
            waitUntil(timeoutMillis = 5_000) { has("payday.dialog") }
            // the program's palette from the start, so the shared popup is in its colors
            assertEquals(ProgramPalette, DdfColors.palette)
            assertEquals(ProgramPalette.text.toArgb(), inkOf("payday.cleanUpLabel"), "Clean up in the text color")
            assertEquals(ProgramPalette.muted.toArgb(), inkOf("payday.diff"), "App shows in the secondary color")
            // disabled: flat grey with the secondary text color
            assertEquals(ProgramPalette.muted.toArgb(), inkOf("payday.go", listOf(ProgramPalette.outline)))
            shot("program-payday-empty", w, h)
            onNodeWithTag("payday.balance", useUnmergedTree = true).performTextInput(Money.plain(PaydayButton.appBalance(m.budget!!, acct) + 2_000_00))
            waitForIdle()
            click("payday.cleanUp")
            click("payday.help")
            assertEquals(ProgramPalette.text.toArgb(), inkOf("payday.cleanUpLabel"))
            assertEquals(ProgramPalette.muted.toArgb(), inkOf("payday.diff"))
            shot("program-payday-filled", w, h)
            click("payday.cancel")
        }
    }

    @Test fun theProgramsDialogs() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            val m = model()
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
            waitForIdle()
            val dialogs = listOf(
                "add-transaction" to Dialog.AddTransaction(), "transfer" to Dialog.Transfer(), "bonus-income" to Dialog.Bonus(),
                "budget-start" to Dialog.BudgetStart, "payday-schedule" to Dialog.NextPayday, "move-money" to Dialog.MoveMoney(),
                "new-envelope" to Dialog.EnvelopeForm(), "new-account" to Dialog.AccountForm(), "backup" to Dialog.Backup, "currency" to Dialog.CurrencyPicker,
            )
            for ((name, d) in dialogs) {
                m.dialog = d
                waitForIdle()
                waitUntil(timeoutMillis = 5_000) { has("dialog.scrim") }
                shot("program-dialog-$name", w, h)
                m.dialog = null
                waitForIdle()
            }
            assertTrue(!has("dialog.scrim"))
        }
    }
}
