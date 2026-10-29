package net.darkrelay.deaddropfinance.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.RuleAction
import net.darkrelay.deaddropfinance.data.RuleOrigin
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.TransactionSource
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.Sorting.RuleDraft
import net.darkrelay.deaddropfinance.sync.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Auto-categorize, the approve flow, learning, returns and adjustments, against a real (in-memory) database.
 * Synthetic payees and amounts only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SortingTest {

    private val d = LocalDate.of(2030, 6, 1)
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private class World(val store: BudgetStore, val a: BudgetActions, val s: Sorting) {
        lateinit var chk: String
        lateinit var food: String
        lateinit var fun_: String

        /** A row as a CSV import puts it in: unsorted, with the bank's own description. */
        suspend fun bank(desc: String, amount: Long, date: LocalDate, account: String = chk, source: TransactionSource = TransactionSource.SYNC, pending: Boolean = false): String {
            val off = store.dao.account(account)?.onBudget == false
            return store.write {
                val t = put(null, TransactionEntity(accountId = account, date = date, payee = desc, amount = amount, importDescription = desc, source = source, pending = pending, createdAt = now, updatedAt = now))
                put(null, TransactionLineEntity(transactionId = t.id, bucket = if (off) LineBucket.OFF_BUDGET else LineBucket.UNSORTED, amount = amount, createdAt = now, updatedAt = now))
                t.id
            }
        }
        suspend fun b() = a.snapshot()
        suspend fun tx(id: String) = store.dao.transaction(id)!!
        suspend fun lines(id: String) = store.dao.linesOf(id)
        suspend fun suggestions() = Suggest.all(b(), store.dao.rulesNow())
    }

    private fun world(block: suspend World.() -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        var tick = 1_000L
        try {
            val store = BudgetStore(db) { ++tick }
            val actions = BudgetActions(store)
            val w = World(store, actions, Sorting(store, actions))
            runBlocking {
                store.ensureSystemEnvelopes()
                w.chk = actions.saveAccount(null, "Checking", AccountType.BANK, 500_00, d)
                w.food = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 200_00))
                w.fun_ = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Fun", EnvelopeMode.FILL_TO_TARGET))
                actions.assign(w.food, 200_00)
                actions.assign(w.fun_, 50_00)
                w.block()
            }
        } finally {
            db.close()
        }
    }

    // ---- approve -------------------------------------------------------------------------------------------------

    @Test fun oneTapApprovesAndApproveAllTakesTheRest() = world {
        s.saveRule(null, RuleDraft("sample market", splits = listOf(Rules.Split(food, 1))))
        val m1 = bank("POS SAMPLE MARKET #101", -12_00, d.plusDays(1))
        val m2 = bank("POS SAMPLE MARKET #102", -30_00, d.plusDays(2))
        val other = bank("SAMPLE UNKNOWN PLACE", -9_00, d.plusDays(2))
        assertEquals(2, s.suggestedCount())
        assertNull("no basis, no suggestion", suggestions()[other])
        assertTrue(s.approve(m1))
        val t = tx(m1)
        assertEquals(SortedBy.APPROVED, t.sortedBy)
        assertNotNull(t.ruleId)
        assertEquals(food, lines(m1).single().envelopeId)
        assertEquals(1, s.approveAll())
        assertEquals(food, lines(m2).single().envelopeId)
        assertEquals(listOf(other), b().toSort.map { it.id })
        assertEquals(200_00L - 42_00, b().envelopeBalance(food))
        assertEquals(0L, b().sortedCheck)
        assertFalse("nothing to approve any more", s.approve(other))
    }

    @Test fun changingTheEnvelopeOrSplittingKeepsTheUsersChoice() = world {
        s.saveRule(null, RuleDraft("sample market", splits = listOf(Rules.Split(food, 1))))
        val x = bank("SAMPLE MARKET", -20_00, d.plusDays(1))
        val y = bank("SAMPLE MARKET", -40_00, d.plusDays(1))
        s.sortByHand(x, fun_)
        assertEquals(SortedBy.HAND, tx(x).sortedBy)
        assertEquals(fun_, lines(x).single().envelopeId)
        a.saveTransaction(y, TransactionDraft(chk, d.plusDays(1), "SAMPLE MARKET", "", -40_00, listOf(LineDraft(food, -25_00), LineDraft(fun_, -15_00))))
        assertEquals(2, lines(y).size)
        // approving everything leaves both alone: they're sorted
        assertEquals(0, s.approveAll())
        assertEquals(fun_, lines(x).single().envelopeId)
        assertEquals(2, lines(y).size)
    }

    // ---- learning ------------------------------------------------------------------------------------------------

    @Test fun offersOnceThenMakesARuleFromThreeIdenticalChoicesWhichCanBeUndone() = world {
        val ids = (1..6).map { bank("SQ *SAMPLE CAFE 00$it", -(4_00L + it), d.plusDays(it.toLong())) }
        val first = s.sortByHand(ids[0], fun_)
        first as Sorting.Learned.Offer
        assertEquals("sample cafe", first.key)
        assertEquals(fun_, first.target)
        assertNull("offered once only", s.sortByHand(ids[1], fun_))
        // two agreeing choices: the next one gets a history suggestion
        assertEquals(Suggest.Basis.HISTORY, suggestions()[ids[2]]!!.basis)
        val made = s.sortByHand(ids[2], fun_)
        made as Sorting.Learned.Made
        assertEquals(RuleOrigin.LEARNED, made.rule.origin)
        assertFalse("shown so it can be undone", made.rule.acknowledged)
        assertEquals("sample cafe", made.rule.matchText)
        assertEquals(Suggest.Basis.RULE, suggestions()[ids[3]]!!.basis)
        // undo: the rule goes and the payee isn't learned again
        s.undoLearned(made.rule.id)
        assertTrue(store.dao.rulesNow().isEmpty())
        s.sortByHand(ids[3], fun_)
        assertNull(s.sortByHand(ids[4], fun_))
        assertTrue(store.dao.rulesNow().isEmpty())
    }

    @Test fun differentChoicesMakeNoRuleAndNoHistorySuggestion() = world {
        val ids = (1..4).map { bank("SAMPLE SHOP", -5_00, d.plusDays(it.toLong())) }
        s.sortByHand(ids[0], fun_)
        s.sortByHand(ids[1], food)
        s.sortByHand(ids[2], fun_)
        assertTrue(store.dao.rulesNow().isEmpty())
        assertNull(suggestions()[ids[3]])
    }

    @Test fun noThanksMeansNeverAndYesMakesAUserRuleFirst() = world {
        s.saveRule(null, RuleDraft("sample other", splits = listOf(Rules.Split(food, 1))))
        val a1 = bank("SAMPLE GYM", -30_00, d.plusDays(1))
        val offer = s.sortByHand(a1, fun_) as Sorting.Learned.Offer
        s.declineOffer(offer)
        (2..4).forEach { s.sortByHand(bank("SAMPLE GYM", -30_00, d.plusDays(it.toLong())), fun_) }
        assertEquals(1, store.dao.rulesNow().size)
        val b1 = bank("SAMPLE BOOKS", -15_00, d.plusDays(1))
        val yes = s.sortByHand(b1, fun_) as Sorting.Learned.Offer
        s.acceptOffer(yes)
        val rules = store.dao.rulesNow()
        assertEquals("sample books", rules[0].matchText)
        assertEquals(RuleOrigin.USER, rules[0].origin)
        assertTrue(rules[0].acknowledged)
        assertEquals(listOf(0, 1), rules.map { it.priority })
    }

    @Test fun handEnteredTransactionsAreNeverLearned() = world {
        val id = a.saveTransaction(null, TransactionDraft(chk, d, "Sample Lunch", "", -8_00, listOf(LineDraft(fun_, -8_00))))
        assertNull(s.learnAfter(id))
        assertEquals(SortedBy.HAND, tx(id).sortedBy)
    }

    // ---- auto-approve --------------------------------------------------------------------------------------------

    @Test fun autoApproveIsOffByDefaultAndAlertsStartQuiet() {
        val st = SyncSettings(ctx.getSharedPreferences("sorting-defaults", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        assertFalse(st.autoApprove)
        assertTrue("the channels are on; each bill's own reminder starts off", st.alertBills && st.alertAutopay)
        assertTrue(st.reminded.isEmpty())
        assertFalse("no amounts on the lock screen unless turned on", st.alertAmountsOnLockScreen)
        assertFalse(st.alertsAsked)
    }

    @Test fun autoApproveTakesRuleMatchesOnlyNotAlwaysAskOrHistory() = world {
        s.saveRule(null, RuleDraft("sample market", splits = listOf(Rules.Split(food, 1))))
        s.saveRule(null, RuleDraft("sample supercenter", splits = listOf(Rules.Split(food, 1)), alwaysAsk = true))
        val h1 = bank("SAMPLE BAKERY", -6_00, d)
        s.sortByHand(h1, fun_)
        val m = bank("SAMPLE MARKET", -10_00, d.plusDays(1))
        val sc = bank("SAMPLE SUPERCENTER 44", -55_00, d.plusDays(1))
        val h2 = bank("SAMPLE BAKERY", -7_00, d.plusDays(1))
        assertEquals(1, s.autoApprove(listOf(m, sc, h2)))
        assertEquals(SortedBy.AUTO, tx(m).sortedBy)
        assertEquals(food, lines(m).single().envelopeId)
        assertEquals(setOf(sc, h2), b().toSort.map { it.id }.toSet())
        assertEquals("still suggested", 2, suggestions().size)
    }

    @Test fun rulesNeverTouchHistoryOutsideTheBudgetOrHandSorted() = world {
        s.saveRule(null, RuleDraft("sample market", splits = listOf(Rules.Split(food, 1)), direction = 0))
        val track = a.saveAccount(null, "Tracked", AccountType.BANK, 0, d, trackOnly = true)
        val archived = a.saveAccount(null, "Old", AccountType.BANK, 0, d)
        a.setAccountArchived(archived, true)
        val late = a.saveAccount(null, "Late start", AccountType.BANK, 0, d.plusDays(30))
        val inTrack = bank("SAMPLE MARKET", -3_00, d.plusDays(1), account = track)
        val inArchived = bank("SAMPLE MARKET", -3_00, d.plusDays(1), account = archived)
        val history = bank("SAMPLE MARKET", -3_00, d.plusDays(1), account = late)            // before its starting balance date
        val byHand = bank("SAMPLE MARKET", -3_00, d.plusDays(1))
        s.sortByHand(byHand, fun_)
        val all = suggestions()
        listOf(inTrack, inArchived, history, byHand).forEach { assertNull(it, all[it]) }
        assertEquals(0, s.autoApprove(listOf(inTrack, inArchived, history, byHand)))
        assertEquals(0, s.approveAll())
        assertEquals(LineBucket.OFF_BUDGET, lines(inTrack).single().bucket)
        assertEquals(LineBucket.UNSORTED, lines(history).single().bucket)
        assertEquals(fun_, lines(byHand).single().envelopeId)
        assertEquals(SortedBy.HAND, tx(byHand).sortedBy)
    }

    // ---- rules ---------------------------------------------------------------------------------------------------

    @Test fun rulesSaveReorderAndCheck() = world {
        val r1 = s.saveRule(null, RuleDraft("sample one", splits = listOf(Rules.Split(food, 1))))
        val r2 = s.saveRule(null, RuleDraft("sample two", splits = listOf(Rules.Split(food, 1))))
        assertEquals(listOf(r2, r1), store.dao.rulesNow().map { it.id })              // a new one goes first
        s.moveRule(r1, up = true)
        assertEquals(listOf(r1, r2), store.dao.rulesNow().map { it.id })
        s.moveRule(r1, up = true)                                                      // already first: nothing
        assertEquals(listOf(r1, r2), store.dao.rulesNow().map { it.id })
        s.saveRule(r2, RuleDraft("Sample Two #55", splits = listOf(Rules.Split(fun_, 1)), minAmount = 1_00, maxAmount = 9_00))
        val e = store.dao.rule(r2)!!
        assertEquals("sample two", e.matchText)
        assertEquals("$fun_:1", e.splits)
        s.deleteRule(r1)
        assertEquals(listOf(r2), store.dao.rulesNow().map { it.id })
        for (bad in listOf(
            RuleDraft("#123 04/05", splits = listOf(Rules.Split(food, 1))),
            RuleDraft("sample", splits = emptyList()),
            RuleDraft("sample", splits = listOf(Rules.Split(food, 1)), minAmount = 9_00, maxAmount = 1_00),
            RuleDraft("sample", splits = listOf(Rules.Split(Rules.RTA, 1)), direction = -1),
        )) {
            try { s.saveRule(null, bad); fail("saved $bad") } catch (e: BudgetRuleException) { /* expected */ }
        }
        val tr = s.saveRule(null, RuleDraft("sample savings", action = RuleAction.TRANSFER, direction = 0))
        assertEquals("", store.dao.rule(tr)!!.splits)
        assertTrue(store.dao.changes().any { it.entityType == "rule" })
    }

    @Test fun aTransferBetweenBudgetAccountsCancelsOut() = world {
        val sav = a.saveAccount(null, "Savings", AccountType.BANK, 100_00, d)
        val rta = b().readyToAssign
        val out = bank("TRANSFER TO SAMPLE SAVINGS", -50_00, d.plusDays(1))
        val inn = bank("TRANSFER FROM SAMPLE CHECKING", 50_00, d.plusDays(1), account = sav)
        s.sortByHand(out, null, transfer = true)
        assertEquals(rta - 50_00, b().readyToAssign)
        s.sortByHand(inn, null, transfer = true)
        assertEquals(rta, b().readyToAssign)
        assertTrue(b().toSort.isEmpty())
        assertEquals(0L, b().sortedCheck)
    }

    // ---- returns -------------------------------------------------------------------------------------------------

    @Test fun aRefundIsSuggestedAsAReturnAndPartialReturnsAddUp() = world {
        val buy = bank("SAMPLE STORE #77 ANYTOWN CA", -60_00, d.plusDays(1))
        s.sortByHand(buy, food)
        val foodAfterBuy = b().envelopeBalance(food)
        val r1 = bank("SAMPLE STORE REFUND", 20_00, d.plusDays(5))
        val sug = suggestions()[r1]!!
        assertEquals(Suggest.Basis.RETURN, sug.basis)
        assertEquals(buy, sug.returnOf!!.id)
        assertEquals(listOf(food to 20_00L), sug.lines.map { it.envelopeId to it.amount })
        assertTrue(s.approve(r1))
        assertEquals(SortedBy.RETURN, tx(r1).sortedBy)
        assertEquals(buy, tx(r1).returnOf)
        assertEquals(foodAfterBuy + 20_00, b().envelopeBalance(food))
        val r2 = bank("SAMPLE STORE CREDIT", 40_00, d.plusDays(6))
        assertEquals(Suggest.Basis.RETURN, suggestions()[r2]!!.basis)
        s.approve(r2)
        assertEquals(60_00L, b().returnedOf(buy))
        val r3 = bank("SAMPLE STORE REFUND", 5_00, d.plusDays(7))
        assertNull("nothing left to return on that purchase", suggestions()[r3])
        try { s.confirmReturn(r3, buy); fail() } catch (e: BudgetRuleException) { /* more than is left */ }
        try { a.markPayday(r1, fill = true); fail() } catch (e: BudgetRuleException) { /* a return isn't income */ }
        assertEquals(0L, b().sortedCheck)
    }

    @Test fun aSplitPurchaseGetsItsReturnBackInProportion() = world {
        val buy = bank("SAMPLE OUTLET", -100_00, d.plusDays(1))
        a.saveTransaction(buy, TransactionDraft(chk, d.plusDays(1), "SAMPLE OUTLET", "", -100_00, listOf(LineDraft(food, -75_00), LineDraft(fun_, -25_00))))
        val back = bank("SAMPLE OUTLET RETURN", 40_00, d.plusDays(3))
        s.approve(back)
        assertEquals(mapOf(food to 30_00L, fun_ to 10_00L), lines(back).associate { it.envelopeId to it.amount })
    }

    @Test fun thisIsAReturnByHandAndNotAReturn() = world {
        val buy = bank("SAMPLE WEB SHOP", -45_00, d.plusDays(1))
        s.sortByHand(buy, fun_)
        val old = bank("SAMPLE WEB SHOP", -45_00, d.minusDays(120))
        s.sortByHand(old, fun_)
        val credit = bank("PAYMENT PROCESSOR CREDIT 8812", 15_00, d.plusDays(4))
        assertNull("a different merchant isn't suggested", suggestions()[credit])
        val picks = Suggest.returnCandidates(b(), tx(credit))
        assertEquals("older than 90 days isn't offered", listOf(buy), picks.map { it.id })
        s.confirmReturn(credit, buy)
        assertEquals(fun_, lines(credit).single().envelopeId)
        s.notAReturn(credit)
        assertNull(tx(credit).returnOf)
        assertTrue(b().toSort.any { it.id == credit })
    }
}
