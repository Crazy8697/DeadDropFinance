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
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The budget start date (1.1.1), with invented data: bank history before it stays in the account balances and in
 * Categories, but never in envelopes, To sort, Ready to Assign or the assigned total.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BudgetStartTest {

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var actions: BudgetActions
    private lateinit var acct: String
    private lateinit var food: String
    private lateinit var groceries: String
    private val day = LocalDate.of(2030, 8, 20)
    private val now = day.atTime(15, 0).toEpochSecond(ZoneOffset.UTC) * 1000

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now }
        actions = BudgetActions(store)
        store.ensureSystemEnvelopes()
        store.ensureCategories()
        acct = actions.saveAccount(null, "Everyday", AccountType.BANK, 1000_00, day.minusDays(90))
        food = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00, everyday = true))
        groceries = actions.snapshot().categories.first { it.name == "Groceries" }.id
        CategoryActions(store).setClaims(food, setOf(groceries))
        Unit
    }

    @After fun tearDown() { db.close() }

    /** A purchase sorted into Food with the Groceries category, as 90 days of linked history would be once sorted. */
    private suspend fun sortedPurchase(date: LocalDate, amount: Long): String = store.write {
        val t = put(null, TransactionEntity(accountId = acct, date = date, payee = "SAMPLE MARKET", amount = -amount, sortedBy = SortedBy.HAND, createdAt = now, updatedAt = now))
        put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.ENVELOPE, envelopeId = food, amount = -amount, categoryId = groceries, createdAt = now, updatedAt = now))
        t.id
    }

    private suspend fun unsorted(date: LocalDate, amount: Long, payee: String = "SAMPLE SHOP"): String = store.write {
        val t = put(null, TransactionEntity(accountId = acct, date = date, payee = payee, amount = amount, createdAt = now, updatedAt = now))
        put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = amount, createdAt = now, updatedAt = now))
        t.id
    }

    private suspend fun income(date: LocalDate, amount: Long): String = store.write {
        val t = put(null, TransactionEntity(accountId = acct, date = date, payee = "SAMPLE PAY", amount = amount, sortedBy = SortedBy.HAND, createdAt = now, updatedAt = now))
        put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.READY_TO_ASSIGN, amount = amount, createdAt = now, updatedAt = now))
        t.id
    }

    private suspend fun rows() = store.dao.transactionsNow().sortedBy { it.id } to store.dao.linesNow().sortedBy { it.id }

    @Test fun purchasesBeforeTheStartLeaveEnvelopesAndToSortButCountInCategories() = runBlocking<Unit> {
        sortedPurchase(day.minusDays(30), 80_00)
        val loose = unsorted(day.minusDays(20), -15_00)
        store.setBudgetStart(day.minusDays(10))
        val b = actions.snapshot()
        assertEquals("the envelope doesn't carry old spending", 0L, b.envelopeBalance(food))
        assertTrue("old ones aren't in To sort", b.toSort.none { it.id == loose })
        assertEquals("the account still shows the bank's balance", 1000_00L - 80_00 - 15_00, b.accountBalance(acct))
        val m = b.months(day).first()
        assertEquals("Categories still counts them, in their own budget month", 95_00L, b.categoryTotal(m))
        assertEquals(80_00L, b.categoryTotals(m).first { it.first?.name == "Groceries" }.second)
        assertEquals(0L, b.sortedCheck)
        assertTrue(b.beforeBudgetStart(b.transactions.first { it.id == loose }))
    }

    @Test fun incomeBeforeTheStartIsNotInReadyToAssignOrTheAssignedTotalAndReadyToAssignStartsAtTheBalance() = runBlocking<Unit> {
        income(day.minusDays(30), 500_00)
        sortedPurchase(day.minusDays(25), 100_00)
        actions.saveBonus(null, 20_00, day.minusDays(15), "Cash", "Sample")
        store.setBudgetStart(day.minusDays(10))
        var b = actions.snapshot()
        // the balance on the start date: 1000 + 500 - 100; the bonus before it isn't in an account and doesn't count
        assertEquals(1400_00L, b.readyToAssign)
        assertEquals(b.accountBalance(acct), b.readyToAssign)
        assertEquals(0L, b.incomeIn(b.months(day).first()))
        assertEquals(0L, b.bonusTotal)
        // from the start on, what's assigned comes off it
        actions.move(null, food, 200_00, date = day)
        b = actions.snapshot()
        assertEquals(1200_00L, b.readyToAssign)
        assertEquals(200_00L, b.envelopeBalance(food))
        assertEquals(0L, b.sortedCheck)
        // income on or after the start counts
        income(day.minusDays(2), 300_00)
        b = actions.snapshot()
        assertEquals(1500_00L, b.readyToAssign)
        assertEquals(300_00L, b.incomeIn(b.months(day).first()))
    }

    @Test fun theUpgradeStartsTheBudgetTodayAndAnOldSortedPurchaseStopsCountingButKeepsItsCategory() = runBlocking<Unit> {
        val old = sortedPurchase(day.minusDays(40), 120_00)
        assertEquals(-120_00L, actions.snapshot().envelopeBalance(food))
        assertNull(store.budgetStart())
        // an install that was already budgeting (it has accounts) gets today; a second start doesn't move it
        assertTrue(store.ensureBudgetStart(day, setupDone = true))
        assertFalse(store.ensureBudgetStart(day.plusDays(5), setupDone = true))
        assertEquals(day, store.budgetStart())
        val b = actions.snapshot()
        assertEquals(0L, b.envelopeBalance(food))
        assertEquals(0L, b.safeToSpend)
        val line = b.lines.single { it.transactionId == old }
        assertEquals(groceries, line.categoryId)
        assertEquals(food, line.envelopeId)
    }

    @Test fun aNewInstallWaitsForSetupToFinish() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db2 = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val s2 = BudgetStore(db2) { now }
            assertFalse(s2.ensureBudgetStart(day, setupDone = false))
            assertNull(s2.budgetStart())
        } finally {
            db2.close()
        }
    }

    @Test fun changingTheStartEarlierOrLaterRecalculatesAndNeverChangesATransaction() = runBlocking<Unit> {
        sortedPurchase(day.minusDays(30), 50_00)
        sortedPurchase(day.minusDays(5), 20_00)
        income(day.minusDays(20), 400_00)
        val before = rows()
        val changes = store.dao.changes().size
        store.setBudgetStart(day.minusDays(40))
        var b = actions.snapshot()
        assertEquals(-70_00L, b.envelopeBalance(food))
        assertEquals(1000_00L + 400_00, b.readyToAssign)
        store.setBudgetStart(day.minusDays(10))
        b = actions.snapshot()
        assertEquals(-20_00L, b.envelopeBalance(food))
        assertEquals(1000_00L - 50_00 + 400_00, b.readyToAssign)
        store.setBudgetStart(day)
        b = actions.snapshot()
        assertEquals(0L, b.envelopeBalance(food))
        assertEquals(b.accountBalance(acct), b.readyToAssign)
        assertEquals(0L, b.sortedCheck)
        assertEquals("no transaction or line changed", before, rows())
        // 1.4.0: each change of the date is logged (so server sync carries it) as the meta entity budget_start, and nothing else
        val added = store.dao.changes().drop(changes)
        assertEquals("only the date was written", List(3) { net.darkrelay.deaddropfinance.data.BudgetStore.META_ENTITY to "budget_start" }, added.map { it.entityType to it.entityId })
    }

    @Test fun theLaterOfTheBudgetStartAndTheAccountsStartingBalanceDateWins() = runBlocking<Unit> {
        // an account that starts after the budget start: its starting balance date wins
        val late = actions.saveAccount(null, "Late", AccountType.BANK, 300_00, day.minusDays(5))
        store.write {
            val t = put(null, TransactionEntity(accountId = late, date = day.minusDays(8), payee = "SAMPLE OLD", amount = -40_00, createdAt = now, updatedAt = now))
            put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.ENVELOPE, envelopeId = food, amount = -40_00, createdAt = now, updatedAt = now))
        }
        sortedPurchase(day.minusDays(15), 30_00)
        store.setBudgetStart(day.minusDays(10))
        val b = actions.snapshot()
        val lateTx = b.transactions.first { it.payee == "SAMPLE OLD" }
        assertTrue("before the account's own start", b.beforeAccountStart(lateTx))
        assertFalse(b.beforeBudgetStart(lateTx))
        assertEquals("its balance is as before", 300_00L, b.accountBalance(late))
        assertEquals("its starting balance goes in as is", 300_00L, b.openingBalance(b.accounts.first { it.id == late }))
        // the older account: the budget start wins
        assertEquals(1000_00L - 30_00, b.openingBalance(b.accounts.first { it.id == acct }))
        assertEquals(0L, b.envelopeBalance(food))
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun aPayeeRuleWithACategoryLabelsHistoryButDoesntSortIt() = runBlocking<Unit> {
        val old = unsorted(day.minusDays(20), -9_00, "SAMPLE TACO")
        store.setBudgetStart(day.minusDays(10))
        val sorting = Sorting(store, actions)
        val eating = actions.snapshot().categories.first { it.name == "Eating out" }.id
        sorting.saveRule(null, Sorting.RuleDraft("sample taco", categoryId = eating))
        val b = actions.snapshot()
        val line = b.lines.single { it.transactionId == old }
        assertEquals(eating, line.categoryId)
        assertEquals("still not sorted", LineBucket.UNSORTED, line.bucket)
        assertTrue(b.toSort.none { it.id == old })
        assertEquals(9_00L, b.categoryTotals(b.months(day).first()).first { it.first?.id == eating }.second)
    }

    @Test fun aDepositBeforeTheStartCantBeMarkedPayday() = runBlocking<Unit> {
        val pay = income(day.minusDays(20), 500_00)
        store.setBudgetStart(day.minusDays(10))
        assertTrue(runCatching { actions.markPayday(pay, day, fill = true) }.exceptionOrNull() is BudgetRuleException)
        assertTrue(actions.snapshot().moves.isEmpty())
    }
}
