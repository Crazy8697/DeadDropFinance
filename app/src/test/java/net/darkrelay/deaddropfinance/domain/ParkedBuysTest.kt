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
import net.darkrelay.deaddropfinance.data.ParkedStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parked buys: the wait, the verdict against Rollover both ways, buying from Rollover or another envelope, keep
 * waiting, drop and the dropped total, and the default wait from Settings. The charge sorting through a real bank
 * sync is in sync/AutoPaydayTest. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ParkedBuysTest {

    private val day = LocalDate.of(2030, 4, 2)
    private val hour = 60L * 60 * 1000
    private val start = 1_900_000_000_000L

    private class W(val store: BudgetStore, val a: BudgetActions, val p: ParkedBuys, val clock: LongArray) {
        lateinit var roll: String
        lateinit var home: String
        suspend fun b() = a.snapshot()
        suspend fun one(id: String) = store.dao.parkedBuy(id)!!
        fun later(ms: Long) { clock[0] += ms }
    }

    private fun world(block: suspend W.() -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val clock = longArrayOf(start)
        try {
            val store = BudgetStore(db) { clock[0] }
            val a = BudgetActions(store)
            val w = W(store, a, ParkedBuys(store, a, { day }, { clock[0] }), clock)
            runBlocking {
                store.ensureSystemEnvelopes()
                a.saveAccount(null, "Sample Checking", AccountType.BANK, 1000_00, day.minusDays(10))
                w.roll = w.b().rollover!!.id
                w.home = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Household", EnvelopeMode.FILL_TO_TARGET, target = 300_00))
                a.move(null, w.roll, 140_00)
                a.move(null, w.home, 250_00)
                w.block()
            }
        } finally {
            db.close()
        }
    }

    private suspend fun expectRule(block: suspend () -> Unit) {
        try { block(); fail("expected a BudgetRuleException") } catch (e: BudgetRuleException) { /* expected */ }
    }

    @Test fun itWaitsItsWaitThenSaysWhetherRolloverCoversIt() = world {
        val lamp = p.park("Sample Lamp", 89_99, 2)
        assertFalse(ParkedBuys.isReady(one(lamp), clock[0]))
        expectRule { p.buy(lamp, roll) }
        later(24 * hour)
        assertFalse("one day in, a 2 day wait isn't over", ParkedBuys.isReady(one(lamp), clock[0]))
        later(24 * hour)
        assertTrue(ParkedBuys.isReady(one(lamp), clock[0]))
        // there's room: Rollover has 140.00, leaving 50.01
        val all = store.dao.parkedBuysNow()
        val fits = ParkedBuys.verdict(one(lamp), b(), all)
        assertTrue(fits.fits)
        assertEquals(140_00L, fits.rollover)
        assertEquals(50_01L, fits.left)
        // not right now: more than Rollover has
        val desk = p.park("Sample Desk", 200_00, 1)
        later(24 * hour)
        val no = ParkedBuys.verdict(one(desk), b(), store.dao.parkedBuysNow())
        assertFalse(no.fits)
        assertEquals(140_00L, no.rollover)
        assertEquals(1, ParkedBuys.ready(store.dao.parkedBuysNow(), clock[0] - 1).size)
        assertEquals(2, ParkedBuys.ready(store.dao.parkedBuysNow(), clock[0]).size)
    }

    @Test fun buyFromRolloverOrFromAnotherEnvelope() = world {
        val lamp = p.park("Sample Lamp", 89_99, 1)
        val desk = p.park("Sample Desk", 60_00, 1)
        later(25 * hour)
        p.buy(lamp, roll)
        val bought = one(lamp)
        assertEquals(ParkedStatus.BOUGHT, bought.status)
        assertEquals(roll, bought.envelopeId)
        assertEquals(day, bought.boughtOn)
        assertNull("the real charge isn't in yet", bought.transactionId)
        assertEquals("no money moves until the charge comes in", 140_00L, b().envelopeBalance(roll))
        // what's bought from Rollover and not charged yet is held back from the next verdict
        val v = ParkedBuys.verdict(one(desk), b(), store.dao.parkedBuysNow())
        assertEquals(50_01L, v.rollover)
        assertFalse(v.fits)
        p.buy(desk, home)
        assertEquals(home, one(desk).envelopeId)
        assertEquals(ParkedStatus.BOUGHT, one(desk).status)
        expectRule { p.buy(desk, roll) }
        assertTrue(ParkedBuys.waiting(store.dao.parkedBuysNow()).isEmpty())
    }

    @Test fun keepWaitingWaitsAnotherDay() = world {
        val lamp = p.park("Sample Lamp", 89_99, 1)
        later(25 * hour)
        assertTrue(ParkedBuys.isReady(one(lamp), clock[0]))
        p.keepWaiting(lamp)
        assertFalse(ParkedBuys.isReady(one(lamp), clock[0]))
        assertEquals(ParkedStatus.WAITING, one(lamp).status)
        later(23 * hour)
        assertFalse(ParkedBuys.isReady(one(lamp), clock[0]))
        later(1 * hour)
        assertTrue(ParkedBuys.isReady(one(lamp), clock[0]))
    }

    @Test fun droppingAddsToTheDroppedTotal() = world {
        val lamp = p.park("Sample Lamp", 89_99, 1)
        val desk = p.park("Sample Desk", 60_00, 7)
        assertEquals(0L, ParkedBuys.droppedTotal(store.dao.parkedBuysNow()))
        p.drop(desk)
        assertEquals("a waiting one can be dropped", 60_00L, ParkedBuys.droppedTotal(store.dao.parkedBuysNow()))
        later(25 * hour)
        p.drop(lamp)
        assertEquals(149_99L, ParkedBuys.droppedTotal(store.dao.parkedBuysNow()))
        assertEquals(ParkedStatus.DROPPED, one(lamp).status)
        expectRule { p.keepWaiting(lamp) }
        assertEquals("nothing moved", 140_00L, b().envelopeBalance(roll))
        // and it's change-logged like the rest of the budget
        assertTrue(store.dao.changes().count { it.entityType == "parked_buy" } >= 4)
    }

    @Test fun theDefaultWaitComesFromSettings() = world {
        assertEquals("1 day out of the box", 1, p.defaultWait())
        val a1 = p.park("Sample Mug", 12_00)
        assertEquals(1, one(a1).waitDays)
        assertEquals(24 * hour, one(a1).readyAt - one(a1).parkedAt)
        p.setDefaultWait(7)
        assertEquals(7, p.defaultWait())
        val a2 = p.park("Sample Kettle", 30_00)
        assertEquals(7, one(a2).waitDays)
        assertEquals(7 * 24 * hour, one(a2).readyAt - one(a2).parkedAt)
        // a wait picked on the form wins over the default
        assertEquals(2, one(p.park("Sample Fan", 25_00, 2)).waitDays)
        expectRule { p.setDefaultWait(3) }
        expectRule { p.park("Sample Fan", 25_00, 5) }
        expectRule { p.park(" ", 25_00) }
        expectRule { p.park("Sample Fan", 0) }
        assertEquals(listOf("1 day", "2 days", "1 week"), ParkedBuys.WAITS.map { ParkedBuys.waitLabel(it) })
    }
}
