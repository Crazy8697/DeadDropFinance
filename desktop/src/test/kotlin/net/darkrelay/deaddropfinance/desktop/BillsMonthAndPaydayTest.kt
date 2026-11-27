package net.darkrelay.deaddropfinance.desktop

import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.BudgetRuleException
import net.darkrelay.deaddropfinance.domain.Forecast
import net.darkrelay.deaddropfinance.domain.PaySchedule
import net.darkrelay.deaddropfinance.domain.Payday
import net.darkrelay.deaddropfinance.domain.PaydayButton
import net.darkrelay.deaddropfinance.domain.Reports
import net.darkrelay.deaddropfinance.ui.ComingUpState
import net.darkrelay.deaddropfinance.ui.ComingUpView
import net.darkrelay.deaddropfinance.ui.comingUp
import net.darkrelay.deaddropfinance.ui.comingUpCount
import net.darkrelay.deaddropfinance.ui.comingUpView
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phone 1.9.9, program 0.15.12, on invented budgets in a temporary folder: Bills one budget month at a time (and a custom
 * range), each bill's This month amount and Last checked, the envelope's target following the month's bill amounts, the
 * bills that went missing from Coming up after 0.15.11 (they were never deleted: the month ended the day before an
 * unmarked scheduled payday), and the Payday button with its balance and Clean up.
 */
class BillsMonthAndPaydayTest {

    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.of(2026, 11, 27)
    private var tick = today.minusDays(40).atTime(9, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private val firstMinus2 = PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2)

    private lateinit var dir: File
    private lateinit var store: BudgetStore
    private lateinit var a: BudgetActions
    private lateinit var bills: BillActions
    private lateinit var acct: String
    private val env = mutableMapOf<String, String>()

    private fun open() = runBlocking {
        dir = tmp.newFolder()
        store = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(dir, clock = { tick += 60_000; tick })).store
        store.ensureSystemEnvelopes(); store.ensureCategories()
        a = BudgetActions(store)
        bills = BillActions(store, a) { today }
        acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 1_000_00, LocalDate.of(2026, 10, 1))
        store.ensureBudgetStart(LocalDate.of(2026, 10, 1), setupDone = true)
        for ((name, target) in listOf("Phone" to 100_00L, "Power" to 150_00L, "Rent" to 900_00L, "Fun" to 60_00L)) {
            env[name] = a.saveEnvelope(null, EnvelopeDraft(name, EnvelopeMode.FILL_TO_TARGET, target = target), LocalDate.of(2026, 10, 1))
        }
    }

    private fun payday(date: LocalDate, amount: Long = 2_000_00) = runBlocking {
        val id = a.saveTransaction(null, TransactionDraft(acct, date, "Sample Payroll Co", "", amount, listOf(LineDraft(null, amount, toReadyToAssign = true))))
        a.markPayday(id, date, fill = false)
        id
    }

    private fun bill(name: String, due: LocalDate, amount: Long, envelope: String?): String = runBlocking {
        store.write { put(null, BillEntity(name = name, envelopeId = envelope, amountDue = amount, dueDate = due, createdAt = now, updatedAt = now)).id }
    }

    private fun b(): Budget = runBlocking { a.snapshot() }
    private fun month(offset: Int = 0) = comingUpView(b(), today, ComingUpState().also { it.offset = offset })
    private fun shown(view: ComingUpView) = comingUp(b(), today, view).map { it.name to it.due.toString() }

    /** 0.15.11's hidden bills: the last payday marked Oct 28, Nov 27 a scheduled payday not marked yet. Coming up shows them. */
    @Test fun billsDueAfterAnUnmarkedScheduledPaydayShow() = runBlocking<Unit> {
        open()
        payday(LocalDate.of(2026, 10, 28))
        val names = listOf("Sample Tel", "Sample Pet", "Sample Rent", "Sample Water", "Sample Insurance", "Sample Electric")
        names.forEach { bill(it, LocalDate.of(2026, 12, 1), 100_00, env.getValue("Rent")) }
        bills.savePaySchedule(firstMinus2)
        val view = assertIs<ComingUpView.Month>(month())
        assertEquals(today, view.window.start); assertEquals(LocalDate.of(2026, 12, 30), view.window.end)
        assertEquals("Dec 2026", view.window.label)
        assertEquals(names.sorted(), shown(view).map { it.first }.sorted())
        assertTrue(shown(view).all { it.second == "2026-12-01" })
        assertEquals("6 unpaid this month", comingUpCount(view, 6))
    }

    @Test fun oneMonthAtATimeAndACustomRange() {
        open()
        payday(today)
        runBlocking { bills.savePaySchedule(firstMinus2) }
        bill("Sample Tel", LocalDate.of(2026, 11, 27), 100_00, env.getValue("Phone"))
        bill("Sample Rent", LocalDate.of(2026, 12, 1), 900_00, env.getValue("Rent"))
        // this month (Nov 27 to Dec 30): each bill once, the earliest unpaid (Tel's Nov 27, not also Dec 27)
        assertEquals(listOf("Sample Tel" to "2026-11-27", "Sample Rent" to "2026-12-01"), shown(month()))
        // forward: Dec 30 to Jan 29
        val next = assertIs<ComingUpView.Month>(month(1))
        assertEquals("Jan 2027", next.window.label)
        assertEquals(listOf("Sample Rent" to "2027-01-01", "Sample Tel" to "2027-01-27"), shown(next))
        // back: Oct 30 (the scheduled payday before) to Nov 27
        val back = assertIs<ComingUpView.Month>(month(-1))
        assertEquals(LocalDate.of(2026, 10, 30), back.window.start)
        // a custom range across two months: every occurrence in it
        val range = comingUpView(b(), today, ComingUpState().also { it.custom = true; it.from = "2026-11-27"; it.to = "2027-01-15" })
        assertEquals(listOf("2026-11-27", "2026-12-01", "2026-12-27", "2027-01-01"), shown(range).map { it.second })
        assertEquals("4 unpaid in this range", comingUpCount(range, 4))
    }

    @Test fun anOverdueBillFromLastMonthIsListed() {
        open()
        payday(today)
        runBlocking { bills.savePaySchedule(firstMinus2) }
        bill("Sample Water", LocalDate.of(2026, 11, 20), 35_00, env.getValue("Power"))
        val items = comingUp(b(), today, month())
        assertEquals(listOf("2026-11-20", "2026-12-20"), items.map { it.due.toString() })
        assertTrue(items.first().due.isBefore(today))
    }

    @Test fun theThisMonthAmountCountsForItsMonthOnly() = runBlocking<Unit> {
        open()
        payday(today)
        bills.savePaySchedule(firstMinus2)
        val power = bill("Sample Power", LocalDate.of(2026, 12, 1), 150_00, env.getValue("Power"))
        val w = assertIs<ComingUpView.Month>(month()).window
        assertNull(comingUp(b(), today, month()).single().typed, "every month starts empty")
        bills.setMonthAmount(power, w.start, 187_43)
        val row = comingUp(b(), today, month()).single()
        assertEquals(187_43L, row.typed); assertEquals(187_43L, row.amount, "the row, exact")
        val checked = assertIsNotNullLong(row.lastChecked)
        // the forecast and the bills calendar use it
        val f = Forecast.build(b(), emptyList(), today, LocalDate.of(2026, 12, 30))
        assertEquals(187_43L, f.plan.days.flatMap { it.outs }.single { it.label.startsWith("Sample Power") }.amount)
        assertEquals(187_43L, Reports.billsCalendar(b(), java.time.YearMonth.of(2026, 12)).single().amount)
        // the envelope's target for this month follows; its standing target and next month don't
        val e = b().envelopes.first { it.id == env.getValue("Power") }
        assertEquals(187_43L, b().monthTarget(e, today))
        assertEquals(150_00L, e.target)
        assertEquals(187_43L - b().envelopeBalance(e.id), Payday.amountFor(e, b().envelopeBalance(e.id), b(), today))
        assertNull(b().monthTarget(e, LocalDate.of(2027, 1, 5)))
        val nextRow = comingUp(b(), today, month(1)).single()
        assertNull(nextRow.typed); assertEquals(150_00L, nextRow.amount)
        // stepping back to the month keeps it; clearing goes back to the normal amount and is a check too
        assertEquals(187_43L, comingUp(b(), today, month()).single().typed)
        bills.setMonthAmount(power, w.start, null)
        val cleared = comingUp(b(), today, month()).single()
        assertNull(cleared.typed); assertEquals(150_00L, cleared.amount)
        assertTrue(cleared.lastChecked!! > checked, "Last checked moves on a clear")
        assertNull(b().monthTarget(e, today))
    }

    @Test fun twoBillsInOneEnvelopeAreSummed() = runBlocking<Unit> {
        open()
        payday(today)
        bills.savePaySchedule(firstMinus2)
        val gas = bill("Sample Gas", LocalDate.of(2026, 12, 3), 80_00, env.getValue("Power"))
        bill("Sample Electric", LocalDate.of(2026, 12, 5), 150_00, env.getValue("Power"))
        bills.setMonthAmount(gas, today, 64_17)
        val e = b().envelopes.first { it.id == env.getValue("Power") }
        assertEquals(64_17L + 150_00L, b().monthTarget(e, today))
    }

    // ---- the Payday button ------------------------------------------------------------------------------------

    private fun withSchedule() = runBlocking { payday(LocalDate.of(2026, 10, 30)); bills.savePaySchedule(firstMinus2) }

    @Test fun aHigherBankBalanceAddsTheDepositAndRunsThePayday() = runBlocking<Unit> {
        open(); withSchedule()
        val app = PaydayButton.appBalance(b(), acct)
        val r = a.payday(acct, app + 2_500_00, cleanUp = false, today)
        val dep = store.dao.transaction(r.depositId)!!
        assertEquals(2_500_00L, dep.amount); assertEquals("Payday", dep.payee); assertEquals(today, dep.date); assertTrue(dep.isPayday)
        assertEquals(2_500_00L, r.added)
        // the next payday comes from the schedule
        assertEquals(LocalDate.of(2026, 12, 30), BillActions(store, a) { today }.prediction().next)
        assertTrue(r.plan.fills.isNotEmpty(), "the fill ran")
    }

    @Test fun anEqualBalanceMarksTheDepositAlreadyInNeverASecond() = runBlocking<Unit> {
        open(); withSchedule()
        val id = a.saveTransaction(null, TransactionDraft(acct, today, "Sample Payroll Co", "", 2_000_00, listOf(LineDraft(null, 2_000_00, toReadyToAssign = true))))
        val count = store.dao.transactionsNow().size
        val r = a.payday(acct, PaydayButton.appBalance(b(), acct), cleanUp = false, today)
        assertEquals(id, r.depositId); assertNull(r.added)
        assertEquals(count, store.dao.transactionsNow().size)
        assertTrue(store.dao.transaction(id)!!.isPayday)
    }

    @Test fun anEqualBalanceWithNoDepositSaysSoAndALowerOneIsBlocked() = runBlocking<Unit> {
        open(); withSchedule()
        val app = PaydayButton.appBalance(b(), acct)
        assertTrue(PaydayButton.check(b(), acct, app).problem!!.contains("no money in"))
        val c = PaydayButton.check(b(), acct, app - 12_34)
        assertEquals("The app is \$12.34 higher than the bank. Import or add the missing transactions first.", c.problem)
        val before = store.dao.transactionsNow().size to store.dao.movesNow().size
        assertFailsWith<BudgetRuleException> { a.payday(acct, app - 12_34, cleanUp = false, today) }
        assertEquals(before, store.dao.transactionsNow().size to store.dao.movesNow().size, "nothing saved")
    }

    @Test fun cleanUpSweepsLeftoversAndCoversOverspendingThenTheFillRuns() = runBlocking<Unit> {
        open(); withSchedule()
        val un = b().unplannedEnvelope!!.id
        val roll = b().rollover!!.id
        a.move(null, env.getValue("Fun"), 50_00, date = today)
        a.move(null, env.getValue("Phone"), 20_00, date = today)
        a.saveTransaction(null, TransactionDraft(acct, today, "Sample Store", "", -50_00, listOf(LineDraft(env.getValue("Phone"), -50_00))))
        val unBefore = b().envelopeBalance(un); val rollBefore = b().envelopeBalance(roll)
        val prev = PaydayButton.preview(b(), acct, PaydayButton.appBalance(b(), acct) + 2_000_00, cleanUp = true, today)!!
        assertEquals(listOf(env.getValue("Fun") to un, un to env.getValue("Phone")), prev.cleanup!!.moves.map { it.fromEnvelopeId to it.toEnvelopeId })
        assertEquals(0L, prev.cleanup!!.uncovered)
        val movesBefore = store.dao.movesNow().size
        val r = a.payday(acct, PaydayButton.appBalance(b(), acct) + 2_000_00, cleanUp = true, today)
        val moves = store.dao.movesNow().drop(movesBefore)
        assertEquals(2, moves.count { it.kind == MoveKind.MOVE }, "two clean up moves, as Move money")
        assertTrue(moves.filter { it.kind == MoveKind.MOVE }.none { it.fromEnvelopeId == roll || it.toEnvelopeId == roll })
        // the clean up: Fun's 50.00 back to Unplanned, Phone's 30.00 overspending covered from it; Unplanned keeps the
        // rest (leftovers carry over, nothing is swept), and Rollover is never touched
        assertEquals(listOf(50_00L, 30_00L), moves.filter { it.kind == MoveKind.MOVE }.map { it.amount })
        assertTrue(r.plan.sweeps.isEmpty())
        assertEquals(rollBefore, b().envelopeBalance(roll))
        val unFill = r.plan.fills.filter { it.toEnvelopeId == un }.sumOf { it.amount }
        assertEquals(unBefore + 20_00 + unFill, b().envelopeBalance(un))
        assertEquals(prev.fill.fills.map { it.toEnvelopeId to it.amount }, r.plan.fills.map { it.toEnvelopeId to it.amount }, "the confirm showed the fill it ran")
        assertEquals(100_00L, b().envelopeBalance(env.getValue("Phone")), "filled to its target from zero")
        assertTrue(store.dao.changes().count { it.entityType == "envelope_move" } >= moves.size, "every move logged, so it syncs")
    }

    @Test fun unplannedTooSmallCoversWhatItCan() = runBlocking<Unit> {
        open(); withSchedule()
        val un = b().unplannedEnvelope!!.id
        a.saveTransaction(null, TransactionDraft(acct, today, "Sample Store", "", -80_00, listOf(LineDraft(env.getValue("Phone"), -80_00))))
        a.move(null, un, 30_00, date = today)
        val c = PaydayButton.cleanup(b())
        assertEquals(30_00L, c.moves.single().amount)
        assertEquals(50_00L, c.uncovered)
        val r = a.payday(acct, PaydayButton.appBalance(b(), acct) + 2_000_00, cleanUp = true, today)
        assertEquals(50_00L, r.uncovered)
    }

    private fun assertIsNotNullLong(v: Long?): Long { assertTrue(v != null); return v!! }
}
