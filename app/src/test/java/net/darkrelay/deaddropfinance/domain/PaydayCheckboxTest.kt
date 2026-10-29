package net.darkrelay.deaddropfinance.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Payday and Fill envelopes boxes on Add transaction (1.3.3), through the engine both the app and the desktop
 * program use. Invented data: dates in 2030, round amounts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PaydayCheckboxTest {

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var a: BudgetActions
    private lateinit var acct: String
    private lateinit var rent: String
    private lateinit var food: String
    private val start = LocalDate.of(2030, 3, 1)
    private var now = start.atTime(9, 0).toEpochSecond(ZoneOffset.UTC) * 1000

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now += 1000; now }
        a = BudgetActions(store)
        store.ensureSystemEnvelopes()
        store.ensureCategories()
        acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 0, start)
        rent = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Rent", EnvelopeMode.FILL_TO_TARGET, target = 800_00), start)
        food = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00), start)
        Unit
    }

    @After fun tearDown() { db.close() }

    private fun pay(date: LocalDate, amount: Long, payee: String = "Sample Payroll Co") =
        TransactionDraft(acct, date, payee, "", amount, listOf(LineDraft(null, amount, toReadyToAssign = true)))

    @Test fun aPaydayStartsTheBudgetMonthOnItsDate() = runBlocking<Unit> {
        val day = LocalDate.of(2030, 3, 13)
        val (id, plan) = a.savePayday(pay(day, 1000_00), fill = false, today = day)
        val b = a.snapshot()
        assertTrue(b.transactions.single { it.id == id }.isPayday)
        assertEquals(listOf(day), b.paydayDates)
        assertEquals(Budget.Month(day, null), b.months(day).first())
        assertTrue("no fill asked for: nothing moved", b.moves.isEmpty() && plan.fills.isEmpty() && plan.sweeps.isEmpty())
        assertEquals(1000_00L, b.readyToAssign)
    }

    @Test fun anEarlyPaydayStartsTheMonthWhereItLands() = runBlocking<Unit> {
        // paid on the 29th instead of the 1st (a weekend), then the 14th: the months start on the dates marked
        val first = LocalDate.of(2030, 3, 29)
        val second = LocalDate.of(2030, 4, 14)
        a.savePayday(pay(first, 900_00), fill = false, today = first)
        a.savePayday(pay(second, 900_00), fill = false, today = second)
        val months = a.snapshot().months(second)
        assertEquals(Budget.Month(second, null), months[0])
        assertEquals(Budget.Month(first, second), months[1])
    }

    @Test fun aPaydayWithFillFillsInOrder() = runBlocking<Unit> {
        val day = LocalDate.of(2030, 3, 13)
        val plan = a.savePayday(pay(day, 1500_00), fill = true, today = day).second
        val b = a.snapshot()
        assertEquals(800_00L, b.envelopeBalance(rent))
        assertEquals(300_00L, b.envelopeBalance(food))
        assertEquals(400_00L, b.readyToAssign)
        assertTrue(plan.shortfalls.isEmpty())
        assertTrue(b.moves.all { it.kind == MoveKind.AUTOFILL && it.date == day })
    }

    @Test fun aShortPaydayFillsWhatItCanAndReportsTheRest() = runBlocking<Unit> {
        val day = LocalDate.of(2030, 3, 13)
        val plan = a.savePayday(pay(day, 900_00), fill = true, today = day).second
        val b = a.snapshot()
        assertEquals(800_00L, b.envelopeBalance(rent))
        assertEquals(100_00L, b.envelopeBalance(food))
        assertEquals(0L, b.readyToAssign)
        assertEquals(listOf(Payday.Shortfall(food, 300_00, 100_00)), plan.shortfalls)
    }

    @Test fun aPaydayWithoutFillLeavesTheEnvelopesAlone() = runBlocking<Unit> {
        val first = LocalDate.of(2030, 3, 1)
        a.savePayday(pay(first, 1500_00), fill = true, today = first)
        val before = a.snapshot()
        val second = LocalDate.of(2030, 3, 15)
        a.savePayday(pay(second, 200_00), fill = false, today = second)
        val after = a.snapshot()
        assertEquals("no sweep, no fill", before.moves.size, after.moves.size)
        assertEquals(before.envelopeBalance(rent), after.envelopeBalance(rent))
        assertEquals(before.readyToAssign + 200_00, after.readyToAssign)
        assertEquals(Budget.Month(second, null), after.months(second).first())
    }

    @Test fun aRefundIsPlainMoneyIn() = runBlocking<Unit> {
        val day = LocalDate.of(2030, 3, 20)
        a.saveTransaction(null, pay(day, 25_00, payee = "Sample Store Refund"))
        val b = a.snapshot()
        assertTrue(b.paydayDates.isEmpty())
        assertEquals(listOf(Budget.Month(null, null)), b.months(day))
        assertTrue(b.moves.isEmpty())
        assertEquals(25_00L, b.readyToAssign)
    }

    @Test fun onlyMoneyInToReadyToAssignCanBeAPaydayAndARefusalSavesNothing() = runBlocking<Unit> {
        val day = LocalDate.of(2030, 3, 13)
        val intoEnvelope = TransactionDraft(acct, day, "Sample Payroll Co", "", 100_00, listOf(LineDraft(food, 100_00)))
        assertThrows(BudgetRuleException::class.java) { runBlocking { a.savePayday(intoEnvelope, fill = true, today = day) } }
        val spending = TransactionDraft(acct, day, "Sample Shop", "", -10_00, listOf(LineDraft(food, -10_00)))
        assertThrows(BudgetRuleException::class.java) { runBlocking { a.savePayday(spending, fill = false, today = day) } }
        store.setBudgetStart(LocalDate.of(2030, 3, 10))
        assertThrows(BudgetRuleException::class.java) { runBlocking { a.savePayday(pay(LocalDate.of(2030, 3, 5), 100_00), fill = false, today = day) } }
        assertTrue("nothing was saved", a.snapshot().transactions.isEmpty())
    }

    @Test fun markAsPaydayOnATransactionStillFills() = runBlocking<Unit> {
        // the existing way (the transaction's own screen): unchanged, it always fills
        val day = LocalDate.of(2030, 3, 13)
        val id = a.saveTransaction(null, pay(day, 1500_00))
        a.markPayday(id, day)
        val b = a.snapshot()
        assertEquals(800_00L, b.envelopeBalance(rent))
        assertFalse(b.paydayDates.isEmpty())
    }
}
