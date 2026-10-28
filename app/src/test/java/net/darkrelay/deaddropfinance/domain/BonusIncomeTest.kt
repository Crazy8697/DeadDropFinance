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
import net.darkrelay.deaddropfinance.data.BONUS_ACCOUNT_ID
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.TransactionKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bonus income (1.0.1), with invented numbers: money outside any account that goes to Ready to Assign. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BonusIncomeTest {

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var actions: BudgetActions
    private val day = LocalDate.of(2030, 8, 20)
    private val now = day.atTime(15, 0).toEpochSecond(ZoneOffset.UTC) * 1000

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now }
        actions = BudgetActions(store)
        runBlocking { store.ensureSystemEnvelopes() }
    }

    @After fun tearDown() { db.close() }

    @Test fun savingAddsToReadyToAssignAndTheOverallBalanceWithoutAnAccount() = runBlocking<Unit> {
        val acct = actions.saveAccount(null, "Everyday", AccountType.BANK, 100_00, day.minusDays(5))
        val before = actions.snapshot()
        assertEquals(100_00L, before.readyToAssign)
        assertEquals(100_00L, before.overallBalance)
        val id = actions.saveBonus(null, 40_00, day, "Cash", "Sample side work")
        val b = actions.snapshot()
        assertEquals(140_00L, b.readyToAssign)
        assertEquals(140_00L, b.overallBalance)
        assertEquals(40_00L, b.bonusTotal)
        assertEquals("no account was created or changed", listOf(acct), b.accounts.map { it.id })
        assertEquals(100_00L, b.accountBalance(acct))
        assertEquals(0L, b.sortedCheck)
        val t = b.transactions.single { it.id == id }
        assertEquals(TransactionKind.BONUS, t.kind)
        assertEquals(BONUS_ACCOUNT_ID, t.accountId)
        assertTrue("nothing to sort", b.toSort.isEmpty())
    }

    @Test fun noPaydayRuns() = runBlocking<Unit> {
        actions.saveAccount(null, "Everyday", AccountType.BANK, 100_00, day.minusDays(5))
        val id = actions.saveBonus(null, 500_00, day, "Gift card", "")
        val b = actions.snapshot()
        assertFalse(b.transactions.single { it.id == id }.isPayday)
        assertTrue("no payday date", b.paydayDates.isEmpty())
        assertTrue("no sweep, fill or other move", b.moves.isEmpty())
        assertEquals(600_00L, b.readyToAssign)
    }

    @Test fun editAndDeleteUpdateTheTotals() = runBlocking<Unit> {
        actions.saveAccount(null, "Everyday", AccountType.BANK, 100_00, day.minusDays(5))
        val id = actions.saveBonus(null, 40_00, day, "Venmo", "Sample")
        actions.saveBonus(id, 25_00, day.minusDays(1), "Other", "Sample, fixed")
        var b = actions.snapshot()
        assertEquals(125_00L, b.readyToAssign)
        assertEquals(125_00L, b.overallBalance)
        val t = b.transactions.single { it.id == id }
        assertEquals("Other", t.payee)
        assertEquals("Sample, fixed", t.memo)
        assertEquals(1, b.transactions.count { it.kind == TransactionKind.BONUS })
        assertEquals(0L, b.sortedCheck)
        actions.deleteTransaction(id)
        b = actions.snapshot()
        assertEquals("deleting takes the amount back off", 100_00L, b.readyToAssign)
        assertEquals(100_00L, b.overallBalance)
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun anAmountAndALabelAreRequired() = runBlocking<Unit> {
        for (bad in listOf(0L, -5_00L)) {
            val e = runCatching { actions.saveBonus(null, bad, day, "Cash", "") }.exceptionOrNull()
            assertTrue("$bad refused", e is BudgetRuleException)
        }
        assertTrue(runCatching { actions.saveBonus(null, 5_00, day, "Checking", "") }.exceptionOrNull() is BudgetRuleException)
        assertTrue(actions.snapshot().transactions.isEmpty())
    }
}
