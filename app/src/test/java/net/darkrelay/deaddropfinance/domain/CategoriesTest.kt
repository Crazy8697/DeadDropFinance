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
import net.darkrelay.deaddropfinance.data.DefaultCategories
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.RuleAction
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
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

/** Categories (1.1.0), with invented data: the default set, Unplanned, claims, sorting by category, rules, totals. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CategoriesTest {

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var actions: BudgetActions
    private lateinit var cats: CategoryActions
    private lateinit var acct: String
    private val day = LocalDate.of(2030, 8, 20)
    private val now = day.atTime(15, 0).toEpochSecond(ZoneOffset.UTC) * 1000

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now }
        actions = BudgetActions(store).also { it.requireCategories = true }
        cats = CategoryActions(store)
        store.ensureSystemEnvelopes()
        store.ensureCategories()
        acct = actions.saveAccount(null, "Everyday", AccountType.BANK, 1000_00, day.minusDays(90))
        Unit
    }

    @After fun tearDown() { db.close() }

    private suspend fun cat(name: String) = actions.snapshot().categories.first { it.name == name }.id

    private suspend fun env(name: String, claims: Set<String> = emptySet()): String {
        val id = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft(name, EnvelopeMode.FILL_TO_TARGET, target = 100_00, everyday = true))
        if (claims.isNotEmpty()) cats.setClaims(id, claims)
        return id
    }

    private fun purchase(date: LocalDate, amount: Long, categoryId: String?, envelopeId: String? = null) =
        TransactionDraft(acct, date, "SAMPLE SHOP", "", -amount, listOf(LineDraft(envelopeId, -amount, categoryId = categoryId)))

    private suspend fun BudgetStore.Writer.newUnsorted(payee: String, amount: Long): String {
        val t = put(null, TransactionEntity(accountId = acct, date = day, payee = payee, amount = amount, createdAt = now, updatedAt = now))
        put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = amount, createdAt = now, updatedAt = now))
        return t.id
    }

    private suspend fun BudgetStore.Writer.paydayAt(d: LocalDate) {
        val t = put(null, TransactionEntity(accountId = acct, date = d, payee = "SAMPLE PAY", amount = 500_00, isPayday = true, sortedBy = SortedBy.HAND, createdAt = now, updatedAt = now))
        put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.READY_TO_ASSIGN, amount = 500_00, createdAt = now, updatedAt = now))
    }

    @Test fun theDefaultSetAndUnplannedExistAfterFirstRunOnceOnly() = runBlocking<Unit> {
        var b = actions.snapshot()
        assertEquals(DefaultCategories.NAMES, b.categories.map { it.name })
        assertEquals(17, b.categories.size)
        val u = b.unplannedEnvelope!!
        assertEquals("Unplanned", u.name)
        assertTrue(u.everyday)
        assertTrue(u.unplanned)
        // again: nothing doubles; a deleted category stays deleted
        cats.delete(cat("Snacks"), moveTo = null)
        assertFalse(store.ensureCategories())
        b = actions.snapshot()
        assertEquals(16, b.categories.size)
        assertEquals(1, b.envelopes.count { it.unplanned })
        assertEquals(1, b.envelopes.count { it.name == "Unplanned" })
    }

    @Test fun anExistingEnvelopeNamedUnplannedIsUsedAsItIs() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db2 = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val s2 = BudgetStore(db2) { now }
            val a2 = BudgetActions(s2)
            s2.ensureSystemEnvelopes()
            val mine = a2.saveEnvelope(null, BudgetActions.EnvelopeDraft("Unplanned", EnvelopeMode.GOAL, goalAmount = 50_00, goalDate = day.plusDays(30)))
            assertTrue("adopted", s2.ensureCategories())
            val b = a2.snapshot()
            assertEquals(mine, b.unplannedEnvelope!!.id)
            assertEquals(EnvelopeMode.GOAL, b.unplannedEnvelope!!.mode)
            assertEquals(1, b.envelopes.count { it.name == "Unplanned" })
        } finally {
            db2.close()
        }
    }

    @Test fun unplannedCountsTowardSafeToSpendAndCantBeDeleted() = runBlocking<Unit> {
        val b0 = actions.snapshot()
        val u = b0.unplannedEnvelope!!
        assertTrue(u in b0.everydayEnvelopes)
        actions.assign(u.id, 40_00)
        assertEquals(40_00L, actions.snapshot().safeToSpend)
        val e = runCatching { actions.setEnvelopeArchived(u.id, true) }.exceptionOrNull()
        assertTrue(e is BudgetRuleException)
        assertFalse(actions.snapshot().envelopes.first { it.id == u.id }.archived)
    }

    @Test fun addRenameAndDeleteWithAMove() = runBlocking<Unit> {
        val id = cats.add("Pets")
        assertTrue(runCatching { cats.add("pets") }.exceptionOrNull() is BudgetRuleException)
        assertTrue(runCatching { cats.add("  ") }.exceptionOrNull() is BudgetRuleException)
        cats.rename(id, "Pet care")
        assertEquals("Pet care", actions.snapshot().category(id)!!.name)
        val food = env("Food", setOf(cat("Groceries")))
        val tx = actions.saveTransaction(null, purchase(day, 12_00, cat("Groceries")))
        val rule = Sorting(store, actions).saveRule(null, Sorting.RuleDraft("sample shop", categoryId = cat("Groceries")))
        // used by a purchase and a rule: it needs somewhere to move them
        assertTrue(runCatching { cats.delete(cat("Groceries"), null) }.exceptionOrNull() is BudgetRuleException)
        assertTrue(runCatching { cats.delete(cat("Groceries"), cat("Groceries")) }.exceptionOrNull() is BudgetRuleException)
        val household = cat("Household")
        cats.delete(cat("Groceries"), moveTo = household)
        val b = actions.snapshot()
        assertNull(b.categories.firstOrNull { it.name == "Groceries" })
        val line = b.lines.single { it.transactionId == tx }
        assertEquals(household, line.categoryId)
        assertEquals("its envelope doesn't change", food, line.envelopeId)
        assertEquals(household, store.dao.rule(rule)!!.categoryId)
    }

    @Test fun aCategoryCantBeClaimedByTwoEnvelopes() = runBlocking<Unit> {
        val gas = cat("Car gas")
        val a = env("Car", setOf(gas))
        val b = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Fun", EnvelopeMode.FILL_TO_TARGET, target = 10_00))
        val e = runCatching { cats.setClaims(b, setOf(gas)) }.exceptionOrNull()
        assertTrue(e is BudgetRuleException)
        assertEquals(a, actions.snapshot().category(gas)!!.envelopeId)
        // released first, it can move
        cats.setClaims(a, emptySet())
        cats.setClaims(b, setOf(gas))
        assertEquals(b, actions.snapshot().category(gas)!!.envelopeId)
        // Unplanned claims nothing; archiving an envelope drops its claims
        val u = actions.snapshot().unplannedEnvelope!!.id
        assertTrue(runCatching { cats.setClaims(u, setOf(cat("Rent"))) }.exceptionOrNull() is BudgetRuleException)
        actions.setEnvelopeArchived(b, true)
        assertNull(actions.snapshot().category(gas)!!.envelopeId)
    }

    @Test fun aPurchaseNeedsACategoryButIncomeTransfersAndBonusDont() = runBlocking<Unit> {
        val food = env("Food")
        assertTrue(runCatching { actions.saveTransaction(null, purchase(day, 5_00, null, envelopeId = food)) }.exceptionOrNull() is BudgetRuleException)
        assertTrue(runCatching { actions.saveTransaction(null, purchase(day, 5_00, null)) }.exceptionOrNull() is BudgetRuleException)
        assertTrue(actions.snapshot().transactions.isEmpty())
        // income to Ready to Assign, a transfer, and bonus income
        actions.saveTransaction(null, TransactionDraft(acct, day, "SAMPLE PAY", "", 200_00, listOf(LineDraft(null, 200_00, toReadyToAssign = true))))
        val other = actions.saveAccount(null, "Savings", AccountType.BANK, 0, day.minusDays(90))
        actions.saveTransfer(null, acct, other, 30_00, day, "")
        actions.saveBonus(null, 20_00, day, "Cash", "")
        assertEquals(3, actions.snapshot().transactions.size)
        // sorting by hand into an envelope alone is refused for a purchase; by category it works
        val t = store.write { newUnsorted("SAMPLE", -9_00) }
        assertTrue(runCatching { actions.sortInto(t, food) }.exceptionOrNull() is BudgetRuleException)
        actions.sortIntoCategory(t, cat("Rent"))
        assertEquals(cat("Rent"), actions.snapshot().lines.single { it.transactionId == t }.categoryId)
    }

    @Test fun aClaimedCategorySortsToItsEnvelopeAndAnUnclaimedOneToUnplanned() = runBlocking<Unit> {
        val food = env("Food", setOf(cat("Groceries")))
        val t1 = actions.saveTransaction(null, purchase(day, 15_00, cat("Groceries")))
        val t2 = actions.saveTransaction(null, purchase(day, 8_00, cat("Gifts")))
        val b = actions.snapshot()
        assertEquals(food, b.lines.single { it.transactionId == t1 }.envelopeId)
        assertEquals(b.unplannedEnvelope!!.id, b.lines.single { it.transactionId == t2 }.envelopeId)
        assertEquals(-15_00L, b.envelopeBalance(food))
        assertEquals(-8_00L, b.envelopeBalance(b.unplannedEnvelope!!.id))
        // the envelope can be changed by hand afterward, and the category stays
        val other = env("Other envelope")
        actions.saveTransaction(t1, purchase(day, 15_00, cat("Groceries"), envelopeId = other))
        val line = actions.snapshot().lines.single { it.transactionId == t1 }
        assertEquals(other, line.envelopeId)
        assertEquals(cat("Groceries"), line.categoryId)
    }

    @Test fun aRuleWithACategoryAppliesItAndACategoryOnlyRuleSortsByTheClaim() = runBlocking<Unit> {
        val food = env("Food", setOf(cat("Groceries")))
        val sorting = Sorting(store, actions)
        sorting.saveRule(null, Sorting.RuleDraft("corner grocer", categoryId = cat("Groceries")))
        val plain = env("Plain")
        sorting.saveRule(null, Sorting.RuleDraft("old shop", splits = listOf(Rules.Split(plain, 1))))
        sorting.saveRule(null, Sorting.RuleDraft("gift place", splits = listOf(Rules.Split(plain, 1)), categoryId = cat("Gifts")))
        val a = store.write { newUnsorted("CORNER GROCER 12", -20_00) }
        val c = store.write { newUnsorted("OLD SHOP 4", -6_00) }
        val d = store.write { newUnsorted("GIFT PLACE", -7_00) }
        assertEquals(3, sorting.approveAll())
        val b = actions.snapshot()
        val la = b.lines.single { it.transactionId == a }
        assertEquals(food, la.envelopeId)
        assertEquals(cat("Groceries"), la.categoryId)
        val lc = b.lines.single { it.transactionId == c }
        assertEquals("an old rule keeps working with no category", plain, lc.envelopeId)
        assertNull(lc.categoryId)
        val ld = b.lines.single { it.transactionId == d }
        assertEquals(plain, ld.envelopeId)
        assertEquals(cat("Gifts"), ld.categoryId)
        val grocer = store.dao.rulesNow().first { it.matchText.contains("grocer") }
        assertTrue(Rules.describe(grocer, { null }) { b.categoryName(it) }.contains("Groceries"))
        assertEquals(RuleAction.ENVELOPE, grocer.action)
    }

    @Test fun splitLinesCarryTheirOwnCategoriesAndAReturnLowersItsCategoryTotal() = runBlocking<Unit> {
        val food = env("Food", setOf(cat("Groceries")))
        val tx = actions.saveTransaction(
            null,
            TransactionDraft(acct, day, "SAMPLE MART", "", -30_00, listOf(LineDraft(null, -20_00, categoryId = cat("Groceries")), LineDraft(null, -10_00, categoryId = cat("Household")))),
        )
        var b = actions.snapshot()
        val ls = b.lines.filter { it.transactionId == tx }.sortedBy { it.sortOrder }
        assertEquals(listOf(cat("Groceries"), cat("Household")), ls.map { it.categoryId })
        assertEquals(food, ls[0].envelopeId)
        val m = b.months(day).first()
        assertEquals(30_00L, b.categoryTotal(m))
        // a 6.00 return of the purchase: back off the categories in proportion
        val credit = store.write { newUnsorted("SAMPLE MART RETURN", 6_00) }
        Sorting(store, actions).confirmReturn(credit, tx)
        b = actions.snapshot()
        assertEquals(24_00L, b.categoryTotal(m))
        assertEquals(16_00L, b.categoryTotals(m).first { it.first?.name == "Groceries" }.second)
        assertEquals(8_00L, b.categoryTotals(m).first { it.first?.name == "Household" }.second)
    }

    @Test fun theCategoriesTotalsForTheCurrentAndAnOlderBudgetMonthAndUncategorized() = runBlocking<Unit> {
        val p1 = day.minusDays(28)
        val p2 = day.minusDays(14)
        val p3 = day.minusDays(1)
        store.write { for (p in listOf(p1, p2, p3)) paydayAt(p) }
        // before 1.1.0: sorted into an envelope, no category
        val e = env("Old envelope")
        store.write {
            val t = put(null, TransactionEntity(accountId = acct, date = p2.plusDays(2), payee = "OLD", amount = -11_00, sortedBy = SortedBy.HAND, createdAt = now, updatedAt = now))
            put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.ENVELOPE, envelopeId = e, amount = -11_00, createdAt = now, updatedAt = now))
        }
        actions.saveTransaction(null, purchase(p2.plusDays(3), 40_00, cat("Rent")))
        actions.saveTransaction(null, purchase(p3, 7_00, cat("Snacks")))
        actions.saveTransaction(null, purchase(p3, 9_00, cat("Rent")))
        val b = actions.snapshot()
        val months = b.months(day)
        assertEquals(listOf<LocalDate?>(p3, p2, p1), months.take(3).map { it.start })
        val current = months[0]
        assertNull(current.end)
        assertEquals(listOf("Rent" to 9_00L, "Snacks" to 7_00L), b.categoryTotals(current).map { it.first!!.name to it.second })
        assertEquals(16_00L, b.categoryTotal(current))
        val older = months[1]
        assertEquals(p3, older.end)
        assertEquals(listOf<Pair<String?, Long>>("Rent" to 40_00L, null to 11_00L), b.categoryTotals(older).map { it.first?.name to it.second })
        assertEquals(51_00L, b.categoryTotal(older))
        assertEquals(1, b.categoryPurchases(older, null).size)
        assertEquals("Uncategorized", b.categoryName(null))
        assertEquals(listOf(40_00L), b.categoryPurchases(older, cat("Rent")).map { it.second })
    }

    @Test fun theAssignedTotalCountsPaydayAndBonusAndFollowsAssigningAndMovingBack() = runBlocking<Unit> {
        store.write { paydayAt(day.minusDays(3)) }
        actions.saveBonus(null, 40_00, day.minusDays(1), "Cash", "Sample")
        val e = env("Food")
        var b = actions.snapshot()
        var m = b.months(day).first()
        assertEquals(540_00L, b.incomeIn(m))
        assertEquals(0L, b.assignedIn(m))
        actions.move(null, e, 100_00, date = day)
        b = actions.snapshot()
        assertEquals(100_00L, b.assignedIn(m))
        actions.move(e, null, 30_00, date = day)
        b = actions.snapshot()
        assertEquals("moving back lowers it", 70_00L, b.assignedIn(m))
        // a new budget month starts fresh
        store.write { paydayAt(day.plusDays(1)) }
        b = actions.snapshot()
        m = b.months(day.plusDays(1)).first()
        assertEquals(day.plusDays(1), m.start)
        assertEquals(500_00L, b.incomeIn(m))
        assertEquals(0L, b.assignedIn(m))
    }
}
