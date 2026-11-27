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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.PaySchedule
import net.darkrelay.deaddropfinance.domain.PaydayButton
import net.darkrelay.deaddropfinance.ui.CLEAN_UP_HELP
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.PhonePalette
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The program's screens for phone 1.9.9 / program 0.15.12, driven as a user would, on an invented budget (today Nov 27
 * 2026, the last payday marked Oct 28, monthly on the 1st, 2 days early): Bills one budget month at a time with This month boxes filled
 * and empty, a later month, a custom range, an envelope's target for the month; and the Payday button's popup empty,
 * with more at the bank, the same, less (blocked), the "?" text and the confirm with Clean up. At 1400 x 900 and
 * 1100 x 700; screenshots only with -Pddf.screens and -Pddf.screensRound=0.15.12.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramBillsMonthAndPaydayScreensTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.12" }?.let { File(it).also { d -> d.mkdirs() } }
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
    private fun ComposeUiTest.shows(s: String) = onAllNodes(hasText(s, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.shot(name: String, w: Int, h: Int) { shots?.let { ImageIO.write(image(), "png", File(it, "$name-${w}x$h.png")) } }

    private lateinit var acct: String
    private val env = mutableMapOf<String, String>()

    /** Sample Checking 1,000.00, a payday of 2,000.00 marked Oct 28, monthly on the 1st 2 days early, four envelopes and five bills. */
    private fun model(): AppModel = runBlocking {
        var tick = LocalDate.of(2026, 10, 1).atTime(9, 0).toEpochSecond(ZoneOffset.UTC) * 1000
        val m = AppModel(Engine.start(tmp.newFolder()) { tick += 60_000; tick }) { today }
        val a = m.actions!!
        val store = m.store()!!
        acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 1_000_00, LocalDate.of(2026, 9, 1))
        store.ensureBudgetStart(LocalDate.of(2026, 9, 1), setupDone = true)
        for ((name, target) in listOf("Phone" to 100_00L, "Power" to 150_00L, "Rent" to 900_00L, "Fun" to 60_00L)) {
            env[name] = a.saveEnvelope(null, EnvelopeDraft(name, EnvelopeMode.FILL_TO_TARGET, target = target), LocalDate.of(2026, 10, 1))
        }
        val pay = a.saveTransaction(null, TransactionDraft(acct, LocalDate.of(2026, 10, 28), "Sample Payroll Co", "", 2_000_00, listOf(LineDraft(null, 2_000_00, toReadyToAssign = true))))
        a.markPayday(pay, LocalDate.of(2026, 10, 28), fill = false)
        val bills = BillActions(store, a) { today }
        bills.savePaySchedule(PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2))
        store.write {
            put(null, BillEntity(id = "tel", name = "Sample Phone Co", envelopeId = env.getValue("Phone"), amountDue = 100_00, dueDate = LocalDate.of(2026, 12, 4), createdAt = now, updatedAt = now))
            put(null, BillEntity(id = "power", name = "Sample Electric", envelopeId = env.getValue("Power"), amountDue = 150_00, dueDate = LocalDate.of(2026, 12, 9), varies = true, createdAt = now, updatedAt = now))
            put(null, BillEntity(id = "rent", name = "Sample Rent", envelopeId = env.getValue("Rent"), amountDue = 900_00, dueDate = LocalDate.of(2026, 12, 1), createdAt = now, updatedAt = now))
            put(null, BillEntity(id = "water", name = "Sample Water", envelopeId = env.getValue("Power"), amountDue = 35_00, dueDate = LocalDate.of(2026, 12, 18), createdAt = now, updatedAt = now))
            put(null, BillEntity(id = "gym", name = "Sample Gym", envelopeId = env.getValue("Fun"), amountDue = 30_00, dueDate = LocalDate.of(2026, 12, 22), createdAt = now, updatedAt = now))
        }
        // this budget month (Nov 27 to Dec 30): the electric bill came in higher, the water bill checked and the same
        val w = m.store()!!.let { a.snapshot().budgetMonths(today).containing(today) }
        bills.setMonthAmount("power", w.start, 182_37)
        bills.setMonthAmount("water", w.start, null)
        a.assign(env.getValue("Fun"), 50_00, null, LocalDate.of(2026, 10, 28))
        a.saveTransaction(null, TransactionDraft(acct, LocalDate.of(2026, 11, 10), "Sample Store", "", -30_00, listOf(LineDraft(env.getValue("Phone"), -30_00, categoryId = store.dao.categoriesNow().first().id))))
        m.refresh()
        m.also { models += it }
    }

    @Test fun billsOneMonthAtATimeAndTheEnvelopesMonthTarget() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            val m = model()
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
            waitForIdle()
            click("nav.BILLS")
            waitUntil(timeoutMillis = 10_000) { shows("unpaid this month") }
            assertEquals("Dec 2026", text("comingUp.label"))
            assertEquals("5 unpaid this month", onAllNodes(hasText("unpaid this month", substring = true), useUnmergedTree = true).fetchSemanticsNodes().first().config[SemanticsProperties.Text].joinToString { it.text })
            // the typed amount counts in the card: 100 + 182.37 + 900 + 35 + 30
            assertTrue(shows("$1,247.37"))
            shot("program-bills-this-month-boxes", w, h)
            click("comingUp.forward")
            assertEquals("Jan 2027", text("comingUp.label"))
            shot("program-bills-later-month", w, h)
            click("comingUp.back")
            click("comingUp.custom")
            m.comingUp.from = "2026-12-01"; m.comingUp.to = "2026-12-10"
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) { shows("unpaid in this range") }
            assertTrue(shows("3 unpaid in this range"))
            shot("program-bills-custom-range", w, h)
            // Budget: Power's target this month is its bills' amounts for the month, 182.37 + 35.00
            click("nav.BUDGET")
            waitUntil(timeoutMillis = 5_000) { has("env.Power") }
            assertTrue(shows("$217.37"))
            click("env.Power")
            waitUntil(timeoutMillis = 5_000) { shows("TARGET THIS MONTH") }
            shot("program-envelope-month-target", w, h)
        }
    }

    @Test fun thePaydayPopup() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            val m = model()
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
            waitForIdle()
            click("top.payday")
            waitUntil(timeoutMillis = 5_000) { has("payday.dialog") }
            val app = PaydayButton.appBalance(m.budget!!, acct)
            assertEquals("App shows ${Money.format(app)}.", text("payday.diff"))
            shot("program-payday-empty", w, h)
            fun type(v: Long) { onNodeWithTag("payday.balance", useUnmergedTree = true).performTextClearance(); onNodeWithTag("payday.balance", useUnmergedTree = true).performTextInput(Money.plain(v)); waitForIdle() }
            // less at the bank than the app shows: blocked, in yellow
            type(app - 25_00)
            assertTrue(text("payday.problem").startsWith("The app is $25.00 higher than the bank."), text("payday.problem"))
            shot("program-payday-negative-blocked", w, h)
            // the same, and no income since the last payday: it says so
            type(app)
            assertTrue(has("payday.problem"))
            shot("program-payday-zero", w, h)
            // more at the bank: the difference becomes the payday deposit
            type(app + 2_000_00)
            assertEquals("App shows ${Money.format(app)}. Difference $2,000.00.", text("payday.diff"))
            assertTrue(!has("payday.problem"))
            shot("program-payday-positive", w, h)
            click("payday.help")
            assertEquals(CLEAN_UP_HELP, text("payday.helpText"))
            shot("program-payday-clean-up-help", w, h)
            click("payday.cleanUp")
            click("payday.go")
            waitUntil(timeoutMillis = 5_000) { has("payday.confirm") }
            val c = text("payday.confirmText")
            assertTrue(c.contains("A deposit of $2,000.00 is added to Sample Checking today") && c.contains("Clean up: 1 leftover back to Unplanned ($50.00), 1 overspent covered from Unplanned ($30.00).") && c.contains("Fill:"), c)
            shot("program-payday-confirm-clean-up", w, h)
            click("payday.confirmCancel")
        }
    }
}
