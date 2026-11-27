package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.PaySchedule
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.PhonePalette
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Payday schedule popup in the program (0.15.11), on Bills' Change payday, with an invented budget whose last
 * payday was Oct 30 2026 and today Nov 27 2026: before a schedule the card makes no two-week guess; monthly on the 1st,
 * 2 days early, moved earlier shows Nov 27, Dec 30, Jan 29; every two weeks shows its own dates around Christmas; Save
 * makes it the schedule and the card says so. At 1400 x 900 and 1100 x 700.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramPayScheduleTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.11" }?.let { File(it).also { d -> d.mkdirs() } }

    private val today = LocalDate.of(2026, 11, 27)

    private fun ComposeUiTest.image(): BufferedImage {
        val roots = onAllNodes(isRoot())
        val img = roots[0].captureToImage().toAwtImage()
        for (k in 1 until roots.fetchSemanticsNodes().size) img.createGraphics().apply { drawImage(roots[k].captureToImage().toAwtImage(), 0, 0, null); dispose() }
        return img
    }

    private fun ComposeUiTest.has(t: String) = onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.text(t: String) = onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
    private fun ComposeUiTest.click(t: String) { onNodeWithTag(t, useUnmergedTree = true).performClick(); waitForIdle() }
    private fun ComposeUiTest.pick(select: String, option: String) { click(select); click("$select.option.$option") }
    private fun ComposeUiTest.shows(s: String) = onAllNodes(hasText(s, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** An invented budget: one account and a payday deposit on Oct 30 2026. */
    private fun model(): AppModel = runBlocking {
        var tick = today.minusDays(40).atTime(9, 0).toEpochSecond(ZoneOffset.UTC) * 1000
        val m = AppModel(Engine.start(tmp.newFolder()) { tick += 60_000; tick }) { today }
        val a = m.actions!!
        val acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 500_00, today.minusDays(60))
        m.store()!!.ensureBudgetStart(today.minusDays(60), setupDone = true)
        val pay = a.saveTransaction(null, TransactionDraft(acct, LocalDate.of(2026, 10, 30), "Sample Payroll Co", "Income", 2000_00, listOf(LineDraft(null, 2000_00, toReadyToAssign = true))))
        a.markPayday(pay, today, fill = false)
        m.refresh()
        m.also { models += it }
    }

    @Test fun thePopupThenSaveThenTheCard() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            val m = model()
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
            waitForIdle()
            click("nav.BILLS")
            waitUntil(timeoutMillis = 10_000) { has("card.nextPayday") && shows("No pay schedule yet") }
            assertTrue(!shows("Guessed"), "no two-week guess is shown")
            click("bills.payday")
            waitUntil(timeoutMillis = 10_000) { has("dialog.payday") }
            assertTrue(shows("Payday schedule"))
            assertEquals("Monthly", text("f.often.value"))
            pick("f.day", "1")
            pick("f.early", "2")
            assertEquals("Move earlier", text("f.move.value"))
            waitForIdle()
            assertEquals("Next three paydays: Nov 27, Dec 30, Jan 29", text("payday.nextThree"))
            assertTrue(shows("If your bank pays before the official date"))
            shots?.let { ImageIO.write(image(), "png", File(it, "program-payday-schedule-monthly-${w}x$h.png")) }
            // every two weeks, from the guessed next payday (Nov 13): Christmas Friday moves to Thursday the 24th
            pick("f.often", "Every two weeks")
            waitUntil(timeoutMillis = 5_000) { has("f.payday") }
            assertEquals("Next three paydays: Nov 27, Dec 11, Dec 24", text("payday.nextThree"))
            shots?.let { ImageIO.write(image(), "png", File(it, "program-payday-schedule-every-two-weeks-${w}x$h.png")) }
            // back to monthly (what was picked is kept), and saved
            pick("f.often", "Monthly")
            assertEquals("Next three paydays: Nov 27, Dec 30, Jan 29", text("payday.nextThree"))
            click("dialog.save")
            waitUntil(timeoutMillis = 10_000) { m.dialog == null && shows("Monthly, the 1st, 2 days early") }
            assertEquals(PaySchedule(PaySchedule.Kind.MONTHLY, 1, 2, false), runBlocking { BillActions(m.store()!!, today = { today }).paySchedule() })
            // Oct 30 was the last payday marked: the schedule's next date after it is today, Nov 27
            assertEquals(today, runBlocking { BillActions(m.store()!!, today = { today }).prediction() }.next)
            shots?.let { ImageIO.write(image(), "png", File(it, "program-bills-card-after-saving-${w}x$h.png")) }
        }
    }

    /**
     * Bills, Coming up (0.15.11): this budget month only (Nov 27 to Dec 30 2026, the schedule's), with what's still
     * unpaid from before listed overdue; a bill due on the next payday is next month's. Invented bills.
     */
    @Test fun comingUpShowsThisBudgetMonth() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            val m = model()
            val store = m.store()!!
            runBlocking {
                val a = m.actions!!
                val acct = store.dao.accountsNow().first().id
                val pay = a.saveTransaction(null, TransactionDraft(acct, today, "Sample Payroll Co", "Income", 2000_00, listOf(LineDraft(null, 2000_00, toReadyToAssign = true))))
                a.markPayday(pay, today, fill = false)
                BillActions(store, a) { today }.savePaySchedule(PaySchedule(PaySchedule.Kind.MONTHLY, 1, 2, false))
                store.write {
                    put(null, net.darkrelay.deaddropfinance.data.BillEntity(id = "phone", name = "Sample Phone", envelopeId = null, amountDue = 100_00, dueDate = LocalDate.of(2026, 11, 27), createdAt = now, updatedAt = now))
                    put(null, net.darkrelay.deaddropfinance.data.BillPaymentEntity(billId = "phone", transactionId = null, amount = 100_00, date = today, periodDue = today, method = net.darkrelay.deaddropfinance.data.PaymentMethod.CASH, createdAt = now, updatedAt = now))
                    put(null, net.darkrelay.deaddropfinance.data.BillEntity(id = "rent", name = "Sample Rent", envelopeId = null, amountDue = 900_00, dueDate = LocalDate.of(2026, 12, 1), createdAt = now, updatedAt = now))
                    put(null, net.darkrelay.deaddropfinance.data.BillEntity(id = "water", name = "Sample Water", envelopeId = null, amountDue = 35_00, dueDate = LocalDate.of(2026, 11, 20), createdAt = now, updatedAt = now))
                    put(null, net.darkrelay.deaddropfinance.data.BillEntity(id = "gym", name = "Sample Gym", envelopeId = null, amountDue = 30_00, dueDate = LocalDate.of(2026, 12, 30), createdAt = now, updatedAt = now))
                }
            }
            m.refresh()
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
            waitForIdle()
            click("nav.BILLS")
            waitUntil(timeoutMillis = 10_000) { shows("unpaid before payday") }
            // the phone bill once (Dec 27; Nov 27's is paid), rent, the water bill twice (Nov 20 overdue, Dec 20), no gym (due on payday)
            assertEquals(1, onAllNodesWithTag("bill.Sample Phone", useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(2, onAllNodesWithTag("bill.Sample Water", useUnmergedTree = true).fetchSemanticsNodes().size)
            assertTrue(has("bill.Sample Rent") && !has("bill.Sample Gym"))
            assertTrue(shows("was due Nov 20, overdue"))
            assertEquals("4 unpaid before payday", onAllNodes(hasText("unpaid before payday", substring = true), useUnmergedTree = true)[0].fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })
            shots?.let { ImageIO.write(image(), "png", File(it, "program-bills-coming-up-${w}x$h.png")) }
        }
    }
}
