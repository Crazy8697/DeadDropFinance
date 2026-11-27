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
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.BudgetRuleException
import net.darkrelay.deaddropfinance.domain.PaySchedule
import net.darkrelay.deaddropfinance.domain.Paydays
import net.darkrelay.deaddropfinance.domain.PaydayButton
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.PhonePalette
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Budget screen's month picker steps forward (phone 1.9.9, program 0.15.12), on invented budgets: the next budget
 * month by the pay schedule (or the app's next payday without one), one ahead and no further; money assigned ahead to
 * it (Assign, Move money, Fill targets) out of the one Ready to assign, so nothing is assigned twice; leftovers and
 * overspending carrying into the new month; marking payday making it the open month with its assignments kept; Clean up
 * after assigning ahead; and the banner when a scheduled payday has come unmarked. At 1400 x 900 and 1100 x 700.
 */
@OptIn(ExperimentalTestApi::class)
class BudgetMonthAheadTest {

    @get:Rule val tmp = TemporaryFolder()

    private var today = LocalDate.of(2026, 11, 20)
    private var tick = LocalDate.of(2026, 10, 1).atTime(9, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private val firstMinus2 = PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2)
    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.12" }?.let { File(it).also { d -> d.mkdirs() } }

    private lateinit var store: BudgetStore
    private lateinit var a: BudgetActions
    private lateinit var bills: BillActions
    private lateinit var acct: String
    private val env = mutableMapOf<String, String>()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    /** Sample Checking 1,000.00 from Oct 1, a payday of 2,000.00 marked Oct 28, four Fill to target envelopes. */
    private suspend fun setUp(st: BudgetStore, act: BudgetActions) {
        store = st; a = act
        bills = BillActions(store, a) { today }
        st.ensureSystemEnvelopes(); st.ensureCategories()
        acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 1_000_00, LocalDate.of(2026, 9, 1))
        st.ensureBudgetStart(LocalDate.of(2026, 9, 1), setupDone = true)
        for ((name, target) in listOf("Phone" to 100_00L, "Power" to 150_00L, "Rent" to 900_00L, "Fun" to 60_00L)) {
            env[name] = a.saveEnvelope(null, EnvelopeDraft(name, EnvelopeMode.FILL_TO_TARGET, target = target), LocalDate.of(2026, 10, 1))
        }
        payday(LocalDate.of(2026, 10, 28))
    }

    private fun open() = runBlocking {
        val st = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(), clock = { tick += 60_000; tick })).store
        setUp(st, BudgetActions(st))
    }

    private suspend fun payday(date: LocalDate, amount: Long = 2_000_00, fill: Boolean = false): String {
        val id = a.saveTransaction(null, TransactionDraft(acct, date, "Sample Payroll Co", "", amount, listOf(LineDraft(null, amount, toReadyToAssign = true))))
        a.markPayday(id, date, fill = fill)
        return id
    }

    private fun b(): Budget = runBlocking { a.snapshot() }

    // ---- the next budget month -----------------------------------------------------------------------------------

    @Test fun theNextMonthFollowsTheScheduleThenTheNextOne() = runBlocking<Unit> {
        open()
        bills.savePaySchedule(firstMinus2)
        val next = b().nextMonth(today)
        assertEquals(LocalDate.of(2026, 11, 27) to LocalDate.of(2026, 12, 30), next.start to next.end)
        assertEquals("Next budget month, Nov 27 to Dec 30", next.nextLabel)
        // Nov 27 marked: that month is the open one, the next is Dec 30 to Jan 29
        today = LocalDate.of(2026, 11, 27)
        payday(today)
        val after = b().nextMonth(today)
        assertEquals(LocalDate.of(2026, 12, 30) to LocalDate.of(2027, 1, 29), after.start to after.end)
        assertEquals("Next budget month, Dec 30 to Jan 29", after.nextLabel)
    }

    @Test fun withoutAScheduleTheAppsNextPaydayStartsIt() = runBlocking<Unit> {
        open()
        // a second payday a month before: monthly on the 28th, worked out from the paydays marked
        payday(LocalDate.of(2026, 9, 28))
        val b = b()
        assertNull(b.paySchedule)
        val predicted = Paydays.predict(b.paydayDates, today).next
        assertEquals(predicted, b.nextMonth(today).start)
        assertEquals(LocalDate.of(2026, 11, 28), b.nextMonth(today).start)
        assertNull(b.paydayDue(today), "no banner without a schedule")
    }

    // ---- assigning ahead ---------------------------------------------------------------------------------------------

    @Test fun assignedAheadThenPaydayMarkedKeepsIt() = runBlocking<Unit> {
        open(); bills.savePaySchedule(firstMinus2)
        val phone = env.getValue("Phone")
        val next = b().nextMonth(today)
        val rta = b().readyToAssign
        val assignedThisMonth = b().assignedIn(b().months(today).first())
        a.assign(phone, 100_00, next.start, today)
        var b = b()
        assertEquals(rta - 100_00, b.readyToAssign, "Ready to assign gave it out now")
        assertEquals(0L, b.envelopeBalance(phone), "this month: not in the envelope")
        assertEquals(100_00L, b.nextBalance(phone), "next month: in it")
        assertEquals(100_00L, b.assignedAhead)
        assertEquals(100_00L, b.assignedAheadTo(phone))
        assertEquals(assignedThisMonth, b.assignedIn(b.months(today).first()), "not this month's assigned")
        assertEquals(0L, b.sortedCheck, "every dollar is somewhere")
        // payday comes a day late (Nov 28): the next month starts, the 100.00 is Phone's there, dated from the payday
        today = LocalDate.of(2026, 11, 28)
        val pay = payday(today, fill = true)
        b = b()
        assertTrue(b.aheadMoves.isEmpty())
        assertEquals(100_00L, b.envelopeBalance(phone), "kept, and the fill counted it (Phone's target is 100.00)")
        val m = b.months(today).first()
        assertEquals(today, m.start)
        assertTrue(b.moves.filter { it.forMonth != null }.all { !it.date.isBefore(today) }, "dated in the new month")
        assertEquals(0L, b.sortedCheck)
        // an undone payday puts it ahead again
        a.unmarkPayday(pay)
        assertEquals(100_00L, b().assignedAhead)
    }

    @Test fun theSameDollarCantBeAssignedToBothMonths() = runBlocking<Unit> {
        open(); bills.savePaySchedule(firstMinus2)
        val next = b().nextMonth(today)
        val rta = b().readyToAssign
        a.assign(env.getValue("Rent"), rta - 50_00, next.start, today)
        assertEquals(50_00L, b().readyToAssign)
        val e = assertFailsWith<BudgetRuleException> { a.assign(env.getValue("Phone"), 60_00, null, today) }
        assertEquals("Only $50.00 is ready to assign.", e.message)
        assertFailsWith<BudgetRuleException> { a.assign(env.getValue("Phone"), 60_00, next.start, today) }
        // Fill targets for next month gives only what's left
        assertEquals(50_00L, a.addToEnvelope(env.getValue("Phone"), today, next))
        assertEquals(0L, b().readyToAssign)
        assertEquals(0L, a.addToEnvelope(env.getValue("Power"), today, next))
    }

    @Test fun fillTargetsForNextMonthUseThatMonthsBillAmounts() = runBlocking<Unit> {
        open(); bills.savePaySchedule(firstMinus2)
        val power = env.getValue("Power")
        val next = b().nextMonth(today)
        val bill = store.write { put(null, BillEntity(name = "Sample Electric", envelopeId = power, amountDue = 150_00, dueDate = LocalDate.of(2026, 12, 5), createdAt = now, updatedAt = now)) }
        bills.setMonthAmount(bill.id, next.start, 182_37)
        assertEquals(182_37L, b().monthTarget(b().envelopes.first { it.id == power }, next.start))
        assertEquals(182_37L, a.addToEnvelope(power, today, next))
        assertEquals(182_37L, b().nextBalance(power))
        assertEquals(0L, b().envelopeBalance(power))
    }

    // ---- carry over -------------------------------------------------------------------------------------------------

    @Test fun leftoversAndOverspendingCarryIntoTheNewMonth() = runBlocking<Unit> {
        open(); bills.savePaySchedule(firstMinus2)
        val fun_ = env.getValue("Fun"); val phone = env.getValue("Phone")
        a.assign(fun_, 50_00, null, today)
        a.saveTransaction(null, TransactionDraft(acct, today, "Sample Store", "", -30_00, listOf(LineDraft(phone, -30_00))))
        val roll = b().rollover!!.id
        val rollBefore = b().envelopeBalance(roll)
        // next month, before payday: what each will start with
        assertEquals(50_00L, b().nextBalance(fun_)); assertEquals(-30_00L, b().nextBalance(phone))
        today = LocalDate.of(2026, 11, 27)
        val plan = a.markPayday(a.saveTransaction(null, TransactionDraft(acct, today, "Sample Payroll Co", "", 2_000_00, listOf(LineDraft(null, 2_000_00, toReadyToAssign = true)))), today, fill = false)
        assertTrue(plan.sweeps.isEmpty())
        assertEquals(50_00L, b().envelopeBalance(fun_), "leftover carried")
        assertEquals(-30_00L, b().envelopeBalance(phone), "overspending carried as negative")
        assertEquals(rollBefore, b().envelopeBalance(roll), "nothing swept into Rollover")
    }

    @Test fun cleanUpAfterAssigningAheadZeroesOnlyThisMonth() = runBlocking<Unit> {
        open(); bills.savePaySchedule(firstMinus2)
        val fun_ = env.getValue("Fun"); val phone = env.getValue("Phone")
        val un = b().unplannedEnvelope!!.id
        a.assign(fun_, 50_00, null, today)
        a.saveTransaction(null, TransactionDraft(acct, today, "Sample Store", "", -30_00, listOf(LineDraft(phone, -30_00))))
        val next = b().nextMonth(today)
        a.assign(phone, 100_00, next.start, today)
        // Clean up sees this month only: Fun's 50.00 back, Phone's -30.00 covered; the 100.00 ahead isn't touched
        val c = PaydayButton.cleanup(b())
        assertEquals(listOf(fun_ to un, un to phone), c.moves.map { it.fromEnvelopeId to it.toEnvelopeId })
        assertEquals(listOf(50_00L, 30_00L), c.moves.map { it.amount })
        today = LocalDate.of(2026, 11, 27)
        val r = a.payday(acct, PaydayButton.appBalance(b(), acct) + 2_000_00, cleanUp = true, today)
        val after = b()
        assertEquals(100_00L, after.envelopeBalance(phone), "zeroed, then the 100.00 assigned ahead; full, so the fill gave it nothing")
        assertTrue(r.plan.fills.none { it.toEnvelopeId == phone })
        assertEquals(60_00L, after.envelopeBalance(fun_), "zeroed, then filled to its 60.00")
        assertTrue(after.aheadMoves.isEmpty())
        assertEquals(0L, after.sortedCheck)
    }

    // ---- the banner ---------------------------------------------------------------------------------------------------

    @Test fun theBannerShowsOnceTheScheduledPaydayHasCome() = runBlocking<Unit> {
        open()
        today = LocalDate.of(2026, 11, 27)
        assertNull(b().paydayDue(today), "no schedule, no banner")
        bills.savePaySchedule(firstMinus2)
        assertNull(b().paydayDue(LocalDate.of(2026, 11, 26)))
        assertEquals(today, b().paydayDue(today))
        assertEquals("Payday was due Nov 27. Mark it with Payday.", paydayDueText(today))
        // it doesn't roll the month: the open month is still Oct 28's
        assertEquals(Budget.Month(LocalDate.of(2026, 10, 28), null), b().months(today).first())
        payday(today)
        assertNull(b().paydayDue(today))
    }

    // ---- the program: the picker, the banner, Assign ahead; screenshots ---------------------------------------------

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

    private fun model(): AppModel = runBlocking {
        val m = AppModel(Engine.start(tmp.newFolder()) { tick += 60_000; tick }) { today }
        setUp(m.store()!!, m.actions!!)
        bills.savePaySchedule(firstMinus2)
        a.assign(env.getValue("Rent"), 900_00, null, LocalDate.of(2026, 10, 28))
        a.assign(env.getValue("Fun"), 40_00, null, LocalDate.of(2026, 10, 28))
        a.saveTransaction(null, TransactionDraft(acct, LocalDate.of(2026, 11, 10), "Sample Store", "", -45_00, listOf(LineDraft(env.getValue("Phone"), -45_00, categoryId = runBlocking { store.dao.categoriesNow() }.first().id))))
        m.refresh()
        m.also { models += it }
    }

    @Test fun thePickerStepsOneMonthAheadAndAssignsToIt() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            today = LocalDate.of(2026, 11, 20)
            val m = model()
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
            waitForIdle()
            assertEquals("This budget month, since Oct 28", text("month.label"))
            assertFalse(has("paydayDue"), "Nov 27 hasn't come")
            shot("program-budget-this-month", w, h)
            click("month.newer")
            assertEquals("Next budget month, Nov 27 to Dec 30", text("month.label"))
            // one ahead and no further
            click("month.newer")
            assertEquals("Next budget month, Nov 27 to Dec 30", text("month.label"))
            // carried over: Rent 900.00, Fun 40.00, Phone -45.00; Assign gives Phone its 100.00 for next month
            assertEquals("-$45.00", text("avail.Phone"))
            click("assign.Phone")
            waitUntil(timeoutMillis = 5_000) { has("dialog.move") }
            assertTrue(shows("Move money, next budget month"))
            onNodeWithTag("f.amount", useUnmergedTree = true).performTextInput("145.00")
            click("dialog.save")
            waitUntil(timeoutMillis = 5_000) { m.dialog == null }
            assertEquals("$100.00", text("avail.Phone"))
            click("fillTargets")
            waitUntil(timeoutMillis = 5_000) { text("avail.Power") == "$150.00" }
            assertEquals(145_00L + 150_00L + 20_00L, m.budget!!.assignedAhead, "Phone, Power and Fun's 20.00 to its 60.00")
            assertTrue(shows("ASSIGNED NEXT MONTH"))
            assertEquals("$315.00", text("card.assigned.value"))
            shot("program-budget-next-month-assigned", w, h)
            // back: this month as it was (none of next month's money in it)
            click("month.older")
            assertEquals("This budget month, since Oct 28", text("month.label"))
            assertEquals("-$45.00", text("avail.Phone"))
            assertEquals("$0.00", text("avail.Power"))
        }
    }

    @Test fun theUnmarkedPaydayBannerThenPayday() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            today = LocalDate.of(2026, 11, 20)
            val m = model()
            runBlocking { a.assign(env.getValue("Phone"), 100_00, m.budget!!.nextMonth(today).start, today) }
            today = LocalDate.of(2026, 11, 28)
            runBlocking { m.refresh() }
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) { has("paydayDue") }
            assertEquals("Payday was due Nov 27. Mark it with Payday.", text("paydayDue.text"))
            assertEquals("This budget month, since Oct 28", text("month.label"), "the month doesn't roll on its own")
            shot("program-budget-payday-due-banner", w, h)
            click("month.newer")
            // -45.00 carried over plus the 100.00 assigned ahead
            assertEquals("$55.00", text("avail.Phone"))
            // marking payday (the Payday button, 2,000.00 more at the bank) makes it the open month, Phone's 100.00 kept
            click("paydayDue.go")
            waitUntil(timeoutMillis = 5_000) { has("payday.balance") }
            onNodeWithTag("payday.balance", useUnmergedTree = true).performTextInput(net.darkrelay.deaddropfinance.domain.Money.plain(PaydayButton.appBalance(m.budget!!, acct) + 2_000_00))
            waitForIdle()
            click("payday.go")
            waitUntil(timeoutMillis = 5_000) { has("payday.confirmGo") }
            click("payday.confirmGo")
            waitUntil(timeoutMillis = 10_000) { has("payday.ok") }
            click("payday.ok")
            waitUntil(timeoutMillis = 5_000) { !has("paydayDue") }
            assertEquals("This budget month, since Nov 28", text("month.label"))
            // kept, and the payday's fill topped it up to its 100.00
            assertEquals("$100.00", text("avail.Phone"))
            assertTrue(m.budget!!.aheadMoves.isEmpty())
        }
    }
}
