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
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.ParkedStatus
import net.darkrelay.deaddropfinance.data.Provider
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.TransactionSource
import net.darkrelay.deaddropfinance.sync.AccountData
import net.darkrelay.deaddropfinance.sync.ProviderAccount
import net.darkrelay.deaddropfinance.sync.ProviderTxn
import net.darkrelay.deaddropfinance.sync.SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 0.15.0 review's fixes: a deleted or vanished transaction takes its payday moves, bill payments and parked buy
 * match with it; a posted amount change carries to its bill payment; the Cash envelope can be renamed. Synthetic data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReviewFixesTest {

    private val day = LocalDate.of(2030, 9, 10)

    private fun world(block: suspend (BudgetStore, BudgetActions, String) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val store = BudgetStore(db)
            runBlocking {
                store.ensureSystemEnvelopes()
                val a = BudgetActions(store)
                block(store, a, a.saveAccount(null, "Sample Checking", AccountType.BANK, 100_00, day.minusDays(60)))
            }
        } finally {
            db.close()
        }
    }

    private suspend fun tx(store: BudgetStore, acct: String, date: LocalDate, amount: Long, payee: String, pending: Boolean = false, ext: String? = null) = store.write {
        val t = put(null, TransactionEntity(accountId = acct, date = date, payee = payee, importDescription = payee, amount = amount, pending = pending, externalId = ext, source = if (ext != null) TransactionSource.SYNC else TransactionSource.MANUAL, createdAt = now, updatedAt = now))
        put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = amount, createdAt = now, updatedAt = now))
        t.id
    }

    @Test fun aDeletedChargeTakesItsBillPaymentAndFreesItsParkedBuy() = world { store, a, acct ->
        val home = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Home", EnvelopeMode.FILL_TO_TARGET))
        val bills = BillActions(store, a) { day }
        val bill = bills.saveBill(null, BillActions.BillDraft("Sample Power", 60_00, day.plusDays(2), envelopeId = home, matchText = "sample power"))
        val pay = tx(store, acct, day, -60_00, "SAMPLE POWER CO")
        assertEquals(1, bills.matchPayments(listOf(pay)))
        val parked = ParkedBuys(store, a, { day }) { 1L }
        val p = parked.park("Sample Lamp", 20_00, 1)
        store.write { val o = store.dao.parkedBuy(p)!!; put(o, o.copy(status = ParkedStatus.BOUGHT, envelopeId = home, boughtOn = day, transactionId = pay, updatedAt = now)) }
        a.deleteTransaction(pay)
        assertTrue("the cycle isn't paid by a deleted charge", store.dao.billPaymentsNow().none { it.billId == bill && !it.deleted })
        assertNull(store.dao.parkedBuy(p)!!.transactionId)
    }

    @Test fun aPendingPaydayTheBankDropsTakesItsSweepAndFillWithIt() = world { store, a, acct ->
        val food = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Groceries", EnvelopeMode.FILL_TO_TARGET, target = 200_00))
        a.markPayday(tx(store, acct, day.minusMonths(1), 1500_00, "SAMPLE EMPLOYER"), day.minusMonths(1))
        val pending = tx(store, acct, day.minusDays(1), 1480_00, "SAMPLE EMPLOYER", pending = true, ext = SyncEngine.externalPrefix(Provider.SIMPLEFIN, "ACT") + "P1")
        a.markPayday(pending, day)
        assertTrue(store.dao.movesNow().any { it.paydayTransactionId == pending && it.kind == MoveKind.AUTOFILL })
        // the bank's list no longer has it and nothing replaces it
        SyncEngine(store).apply(Provider.SIMPLEFIN, "ACT", acct, AccountData(ProviderAccount("ACT", "Sample"), emptyList()), day.minusDays(5), day)
        assertTrue(store.dao.movesNow().none { it.paydayTransactionId == pending && !it.deleted })
        val b = a.snapshot()
        assertEquals(1, b.paydayDates.size)
        assertEquals(0L, b.sortedCheck)
        assertEquals(200_00L, b.envelopeBalance(food))
    }

    @Test fun aPostedAmountChangeCarriesToItsBillPayment() = world { store, a, acct ->
        val bills = BillActions(store, a) { day }
        bills.saveBill(null, BillActions.BillDraft("Sample Water", 40_00, day.plusDays(1), matchText = "sample water", anyAmount = true))
        val id = tx(store, acct, day, -40_00, "SAMPLE WATER", pending = true, ext = SyncEngine.externalPrefix(Provider.SIMPLEFIN, "ACT") + "W1")
        bills.matchPayments(listOf(id))
        SyncEngine(store).apply(Provider.SIMPLEFIN, "ACT", acct, AccountData(ProviderAccount("ACT", "Sample"), listOf(ProviderTxn("W1", day, -42_50, "SAMPLE WATER", false))), day.minusDays(5), day)
        assertEquals(42_50L, store.dao.billPaymentsNow().single { it.transactionId == id }.amount)
    }

    @Test fun theCashEnvelopeCanBeRenamed() = world { store, a, _ ->
        val cash = store.write { put(null, EnvelopeEntity(name = "Cash", mode = EnvelopeMode.CASH, sortOrder = 5, createdAt = now, updatedAt = now)).id }
        a.saveEnvelope(cash, BudgetActions.EnvelopeDraft("Sample Wallet", EnvelopeMode.CASH))
        assertEquals("Sample Wallet", store.dao.envelope(cash)!!.name)
        assertEquals(EnvelopeMode.CASH, store.dao.envelope(cash)!!.mode)
        try { a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Another Cash", EnvelopeMode.CASH)); org.junit.Assert.fail() } catch (e: BudgetRuleException) { /* still never made by hand */ }
    }
}
