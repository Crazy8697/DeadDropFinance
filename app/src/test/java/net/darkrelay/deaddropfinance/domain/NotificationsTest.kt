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
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.TransactionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bank notification reading: parsing and the confirm-once flow, logging as pending, the match with bank sync (never
 * a second row), and a deposit notification starting payday once. Every notification text here is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationsTest {

    // ---- parsing (pure) ---------------------------------------------------------------------------------------------

    private val spend1 = "Sample Bank: A purchase of $42.18 was made at SAMPLE MARKET 0042 with card ending 0000."
    private val spend2 = "Sample Bank: A purchase of $7.50 was made at OTHER SAMPLE CAFE with card ending 0000."
    private val deposit1 = "Sample Bank: Direct deposit of $1,480.00 received from SAMPLE EMPLOYER. Available balance $2,010.33."

    @Test fun aGenericGuessIsShownFirst() {
        val g = NotifParse.guess(spend1)!!
        assertEquals(42_18L, g.amount)
        assertEquals("SAMPLE MARKET 0042", g.payee)
        assertFalse(g.deposit)
        val dep = NotifParse.guess(deposit1)!!
        assertEquals(1480_00L, dep.amount)
        assertTrue(dep.deposit)
        assertEquals("SAMPLE EMPLOYER", dep.payee)
        assertNull("no money in it", NotifParse.guess("Sample Bank: your statement is ready to view."))
        assertEquals(listOf(1480_00L, 2010_33L), NotifParse.amounts(deposit1).map { it.cents })
    }

    @Test fun onceConfirmedTheTemplateReadsTheNextOnesByItself() {
        val amounts = NotifParse.amounts(spend1)
        val t = NotifParse.learn(spend1, amounts.first(), "SAMPLE MARKET 0042", deposit = false)
        val p = NotifParse.parse(spend2, listOf(t))!!
        assertEquals(7_50L, p.amount)
        assertEquals("OTHER SAMPLE CAFE", p.payee)
        assertFalse(p.deposit)
        // a deposit template next to it; the user picked the first amount (not the balance)
        val dt = NotifParse.learn(deposit1, NotifParse.amounts(deposit1)[0], "SAMPLE EMPLOYER", deposit = true)
        val other = "Sample Bank: Direct deposit of $1,512.40 received from SAMPLE EMPLOYER. Available balance $99.00."
        val pd = NotifParse.parse(other, listOf(t, dt))!!
        assertTrue(pd.deposit)
        assertEquals(1512_40L, pd.amount)
        assertEquals("SAMPLE EMPLOYER", pd.payee)
        // a notification that doesn't fit any template isn't logged
        assertNull(NotifParse.parse("Sample Bank: your password was changed.", listOf(t, dt)))
        // a payee the user typed (not in the text) is kept as is
        val fixed = NotifParse.learn("Sample Wallet: you paid $3.00", NotifParse.amounts("Sample Wallet: you paid $3.00")[0], "Sample Vending", false)
        assertEquals("Sample Vending", NotifParse.parse("Sample Wallet: you paid $4.25", listOf(fixed))!!.payee)
        // stored and read back
        assertEquals(listOf(t, dt, fixed), NotifParse.decode(NotifParse.encode(listOf(t, dt, fixed))))
    }

    // ---- logging and matching (database) ---------------------------------------------------------------------------

    private val day = LocalDate.of(2030, 5, 15)
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private class W(val store: BudgetStore, val a: BudgetActions, val bills: BillActions, val log: NotifLog) {
        lateinit var chk: String
        lateinit var food: String
        suspend fun b() = a.snapshot()
    }

    private fun world(today: LocalDate = day, block: suspend W.() -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        var tick = 10_000_000L
        try {
            val store = BudgetStore(db) { tick }
            val actions = BudgetActions(store)
            val w = W(store, actions, BillActions(store, actions) { today }, NotifLog(store, actions, { today }, { tick += 1000; tick }))
            runBlocking {
                store.ensureSystemEnvelopes()
                w.chk = actions.saveAccount(null, "Checking", AccountType.BANK, 500_00, day.minusDays(60))
                w.food = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00))
                w.bills.pickApp("com.example.samplebank", "Sample Bank", w.chk)
                w.block()
            }
        } finally {
            db.close()
        }
    }

    @Test fun aSpendNotificationIsLoggedPendingWithItsSuggestionAndOnlyOnce() = world {
        Sorting(store, a).saveRule(null, Sorting.RuleDraft("sample market", splits = listOf(Rules.Split(food, 1))))
        val p = NotifParse.Parsed(42_18, "SAMPLE MARKET 0042", deposit = false)
        val r = log.log("com.example.samplebank", p)
        val t = store.dao.transaction(r.txId!!)!!
        assertTrue(t.pending)
        assertEquals(TransactionSource.NOTIFICATION, t.source)
        assertEquals(-42_18L, t.amount)
        assertEquals(Suggest.Basis.RULE, Suggest.forTx(b(), store.dao.rulesNow(), t)!!.basis)
        // the parsed result is kept for the test button (never the text)
        val app = store.dao.notifApp("com.example.samplebank")!!
        assertEquals(42_18L, app.lastAmount)
        assertEquals("SAMPLE MARKET 0042", app.lastPayee)
        // the same notification again (an update) isn't a second purchase
        assertTrue(log.log("com.example.samplebank", p).duplicate)
        assertEquals(1, b().transactions.count { it.source == TransactionSource.NOTIFICATION })
        // an app that isn't picked, or has no account, logs nothing
        assertTrue(log.log("com.example.other", p).noAccount)
    }

    @Test fun aDepositNotificationStartsNoPayday() = world {
        // last month's payday, marked by hand
        val last = store.write {
            val t = put(null, TransactionEntity(accountId = chk, date = day.minusMonths(1), payee = "SAMPLE EMPLOYER", importDescription = "SAMPLE EMPLOYER", amount = 1500_00, source = TransactionSource.CSV, createdAt = now, updatedAt = now))
            put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = 1500_00, createdAt = now, updatedAt = now))
            t.id
        }
        a.markPayday(last, day.minusMonths(1))
        val moves = store.dao.movesNow().size
        val r = log.log("com.example.samplebank", NotifParse.Parsed(1480_00, "SAMPLE EMPLOYER", deposit = true))
        val dep = store.dao.transaction(r.txId!!)!!
        assertFalse("payday is marked by hand only", dep.isPayday)
        assertEquals(1, b().paydayDates.size)
        assertEquals(moves, store.dao.movesNow().size)
        assertEquals(0L, b().sortedCheck)
    }

    @Test fun anEntryNoImportBringsIsFlaggedAfterAWeek() = world {
        val id = log.log("com.example.samplebank", NotifParse.Parsed(9_99, "SAMPLE KIOSK", deposit = false)).txId!!
        val b = b()
        assertNotNull(NotifLog.stale(b, day.plusDays(8)).firstOrNull { it.id == id })
        assertTrue(NotifLog.stale(b, day.plusDays(3)).isEmpty())
    }
}
