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
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.TransactionSource
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
 * Payday from the deposit landing, with no tap: through a CSV import, and once only when a bank notification saw the
 * same deposit first (then CSV). Also a bought parked buy's charge sorted against its envelope when it's imported.
 * Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AutoPaydayTest {

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var a: BudgetActions
    private val day = LocalDate.of(2030, 9, 10)
    private val now = day.atTime(15, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private lateinit var acct: String
    private lateinit var food: String

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now }
        a = BudgetActions(store)
        runBlocking {
            store.ensureSystemEnvelopes()
            acct = a.saveAccount(null, "Everyday", AccountType.BANK, 100_00, day.minusDays(45))
            food = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Groceries", EnvelopeMode.FILL_TO_TARGET, target = 300_00, everyday = true))
            // last month's payday, marked by hand: Groceries filled to 300, then 50 spent from it
            val last = deposit(day.minusMonths(1).minusDays(1), 1500_00)
            a.markPayday(last, day.minusMonths(1).minusDays(1))
            val spent = store.write {
                val t = put(null, TransactionEntity(accountId = acct, date = day.minusDays(20), payee = "SAMPLE MARKET", amount = -50_00, source = TransactionSource.MANUAL, createdAt = now, updatedAt = now))
                put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = -50_00, createdAt = now, updatedAt = now))
                t.id
            }
            a.sortInto(spent, food)
        }
    }

    @After fun tearDown() { db.close() }

    private suspend fun deposit(date: LocalDate, amount: Long): String = store.write {
        val t = put(null, TransactionEntity(accountId = acct, date = date, payee = "SAMPLE EMPLOYER", importDescription = "SAMPLE EMPLOYER", amount = amount, source = TransactionSource.MANUAL, createdAt = now, updatedAt = now))
        put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = amount, createdAt = now, updatedAt = now))
        t.id
    }

    private suspend fun paydayMoves() = store.dao.movesNow().filter { it.kind == MoveKind.SWEEP || it.kind == MoveKind.AUTOFILL }

    @Test fun paydayFromCsvRunsByItself() = runBlocking<Unit> {
        val csv = "Date,Description,Amount\n${day.minusDays(1)},SAMPLE EMPLOYER PAYROLL,1480.00\n${day.minusDays(1)},SAMPLE CAFE,-4.50\n"
        val res = a.importCsv(acct, CsvImport.plan(CsvImport.read(csv, CsvImport.detect(csv)), acct, a.snapshot().transactions))
        // what the import screen runs after an import
        val ran = Arrivals(store, a, { day }).landed(res.newIds, autoApprove = false)
        assertEquals(1, ran.size)
        val b = a.snapshot()
        assertTrue(b.transactions.first { it.id == ran.single() }.isPayday)
        assertEquals(2, b.paydayDates.size)
        assertEquals(250_00L, b.envelopeBalance(b.rollover!!.id))
        assertEquals(300_00L, b.envelopeBalance(food))
        assertEquals("the cafe charge isn't a payday", 1, b.toSort.size)
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun aNotificationThenCsvOrAnUnmatchedSecondRowRunsPaydayOnce() = runBlocking<Unit> {
        BillActions(store, a) { day }.pickApp("com.example.samplebank", "Sample Bank", acct)
        val r = NotifLog(store, a, { day }, { now }).log("com.example.samplebank", NotifParse.Parsed(1480_00, "SAMPLE EMPLOYER", deposit = true))
        assertTrue(r.payday)
        val moves = paydayMoves().size
        // CSV: the file's row posts the pending entry instead of adding one
        val csv = "Date,Description,Amount\n$day,SAMPLE EMPLOYER,1480.00\n"
        val res = a.importCsv(acct, CsvImport.plan(CsvImport.read(csv, CsvImport.detect(csv)), acct, a.snapshot().transactions))
        assertTrue(res.newIds.isEmpty())
        assertTrue(Arrivals(store, a, { day }).landed(res.newIds, autoApprove = false).isEmpty())
        // even a second row of the same deposit (a bank that names it differently) doesn't start payday again
        val second = deposit(day, 1480_00)
        assertTrue(Arrivals(store, a, { day }).paydays(listOf(second)).isEmpty())
        assertFalse(store.dao.transaction(second)!!.isPayday)
        assertEquals(moves, paydayMoves().size)
        assertEquals(2, a.snapshot().paydayDates.size)
    }

    @Test fun aBoughtParkedBuysChargeIsSortedToItsEnvelopeWhenItsImported() = runBlocking<Unit> {
        val fun_ = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Fun", EnvelopeMode.FILL_TO_TARGET))
        val clock = longArrayOf(now - 3L * ParkedBuys.DAY_MS)
        val parked = ParkedBuys(store, a, { day }, { clock[0] })
        val lamp = parked.park("Sample Lamp", 89_99, 2)
        clock[0] = now
        parked.buy(lamp, fun_)
        // the store charges tax on top; a grocery charge the same day is left alone
        val csv = "Date,Description,Amount\n$day,SAMPLE HARDWARE 0012,-95.39\n$day,SAMPLE MARKET,-12.00\n"
        val res = a.importCsv(acct, CsvImport.plan(CsvImport.read(csv, CsvImport.detect(csv)), acct, a.snapshot().transactions))
        assertEquals(2, res.newIds.size)
        Arrivals(store, a, { day }, { now }).landed(res.newIds, autoApprove = false)
        val p = store.dao.parkedBuy(lamp)!!
        val charge = store.dao.transaction(p.transactionId!!)!!
        assertEquals(-95_39L, charge.amount)
        assertEquals(SortedBy.AUTO, charge.sortedBy)
        assertEquals(fun_, store.dao.linesOf(charge.id).single().envelopeId)
        assertEquals(-95_39L, a.snapshot().envelopeBalance(fun_))
        val other = res.newIds.first { it != charge.id }
        assertNull(store.dao.transaction(other)!!.sortedBy)
        assertEquals(0L, a.snapshot().sortedCheck)
    }
}
