package net.darkrelay.deaddropfinance.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.KeyWrapper
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.SecretStore
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.TransactionSource
import net.darkrelay.deaddropfinance.domain.Arrivals
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.CsvImport
import net.darkrelay.deaddropfinance.domain.NotifLog
import net.darkrelay.deaddropfinance.domain.NotifParse
import net.darkrelay.deaddropfinance.domain.ParkedBuys
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
 * Payday from the deposit landing, with no tap: through a real bank sync (against the local SimpleFIN mock), through
 * a CSV import, and once only when a bank notification saw the same deposit first (then sync or CSV). Also a bought
 * parked buy's charge sorted against its envelope when it syncs in. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AutoPaydayTest {

    private class FakeWrapper : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x21).toByte() }
        override fun unwrap(blob: ByteArray) = blob.drop(1).map { (it.toInt() xor 0x21).toByte() }.toByteArray()
    }

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var a: BudgetActions
    private lateinit var secrets: SecretStore
    private lateinit var settings: SyncSettings
    private val day = LocalDate.of(2030, 9, 10)
    private val now = day.atTime(15, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private val closers = mutableListOf<AutoCloseable>()
    private var txns: List<String> = emptyList()
    private lateinit var acct: String
    private lateinit var food: String

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now }
        a = BudgetActions(store)
        secrets = SecretStore(File(Files.createTempDirectory("ddf-payday").toFile(), SecretStore.FILE_NAME), FakeWrapper())
        settings = SyncSettings(ctx.getSharedPreferences("payday-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
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

    @After fun tearDown() { closers.forEach { it.close() }; db.close() }

    private suspend fun deposit(date: LocalDate, amount: Long): String = store.write {
        val t = put(null, TransactionEntity(accountId = acct, date = date, payee = "SAMPLE EMPLOYER", importDescription = "SAMPLE EMPLOYER", amount = amount, source = TransactionSource.MANUAL, createdAt = now, updatedAt = now))
        put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = amount, createdAt = now, updatedAt = now))
        t.id
    }

    private fun e(d: LocalDate) = d.atTime(9, 0).toEpochSecond(ZoneOffset.UTC)

    /** A connection linked to the account; the bank's list is whatever [txns] holds at each sync. */
    private suspend fun linked(): Pair<SyncService, String> {
        val m = SimpleFinMock().also { closers += it }
        m.accountsAnswer = { q -> Answer(200, SimpleFinMock.accountSet("100.00", if (q.contains("start-date=")) txns else emptyList())) }
        val s = SyncService(
            store, secrets, settings, clock = { now }, zone = ZoneOffset.UTC,
            httpFor = { Http(sleep = {}, connectTimeoutMs = 2000, readTimeoutMs = 3000, baseBackoffMs = 1) }, allowLoopbackHttp = true,
        )
        val conn = s.connectSimpleFin(m.setupToken())
        s.link(listOf(SyncService.LinkChoice(store.sync.linkedOf(conn).single().id, SyncService.Target.Existing(acct), SyncService.History.DAYS_30)))
        return s to conn
    }

    private suspend fun paydayMoves() = store.dao.movesNow().filter { it.kind == MoveKind.SWEEP || it.kind == MoveKind.AUTOFILL }

    @Test fun paydayFromBankSyncRunsByItself() = runBlocking<Unit> {
        assertEquals(250_00L, a.snapshot().envelopeBalance(food))
        val (s, conn) = linked()
        txns = listOf(SimpleFinMock.txn("D1", e(day.minusDays(1)), "1480.00", "SAMPLE EMPLOYER PAYROLL"))
        val r = s.syncConnection(conn)
        assertTrue(r.ok)
        val dep = store.dao.transaction(r.outcome.newIds.single())!!
        assertTrue("payday ran with no tap", dep.isPayday)
        val b = a.snapshot()
        assertEquals(2, b.paydayDates.size)
        // Rollover sweep, then auto-fill
        assertEquals(250_00L, b.envelopeBalance(b.rollover!!.id))
        assertEquals(300_00L, b.envelopeBalance(food))
        assertEquals(LineBucket.READY_TO_ASSIGN, store.dao.linesOf(dep.id).single().bucket)
        assertTrue(b.toSort.none { it.id == dep.id })
        assertEquals(0L, b.sortedCheck)
        // the next sync brings the same deposit again: nothing runs twice
        val before = paydayMoves().size
        val r2 = s.syncConnection(conn)
        assertTrue(r2.outcome.newIds.isEmpty())
        assertEquals(before, paydayMoves().size)
        assertEquals(2, a.snapshot().paydayDates.size)
    }

    @Test fun paydayFromCsvRunsByItself() = runBlocking<Unit> {
        val csv = "Date,Description,Amount\n${day.minusDays(1)},SAMPLE EMPLOYER PAYROLL,1480.00\n${day.minusDays(1)},SAMPLE CAFE,-4.50\n"
        val res = a.importCsv(acct, CsvImport.plan(CsvImport.read(csv, CsvImport.detect(csv)), acct, a.snapshot().transactions))
        // what the import screen runs after an import, the same as bank sync
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

    @Test fun aNotificationThenSyncForTheSameDepositRunsPaydayOnce() = runBlocking<Unit> {
        val bills = BillActions(store, a) { day }
        bills.pickApp("com.example.samplebank", "Sample Bank", acct)
        val log = NotifLog(store, a, { day }, { now })
        val r = log.log("com.example.samplebank", NotifParse.Parsed(1480_00, "SAMPLE EMPLOYER", deposit = true))
        assertTrue(r.payday)
        val moves = paydayMoves().size
        val rollover = a.snapshot().let { it.envelopeBalance(it.rollover!!.id) }
        // the bank's record comes in through sync, a little different: the same row, no second payday
        val (s, conn) = linked()
        txns = listOf(SimpleFinMock.txn("D1", e(day), "1482.15", "SAMPLE EMPLOYER PAYROLL"))
        val out = s.syncConnection(conn)
        assertTrue(out.outcome.newIds.isEmpty())
        val b = a.snapshot()
        assertEquals(2, b.paydayDates.size)
        assertEquals(1, b.transactions.count { it.isPayday && it.date.isAfter(day.minusDays(5)) })
        assertEquals(moves, paydayMoves().size)
        assertEquals(rollover, b.envelopeBalance(b.rollover!!.id))
        assertEquals(1482_15L, store.dao.transaction(r.txId!!)!!.amount)
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

    @Test fun aBoughtParkedBuysChargeIsSortedToItsEnvelopeWhenItSyncs() = runBlocking<Unit> {
        val fun_ = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Fun", EnvelopeMode.FILL_TO_TARGET))
        val clock = longArrayOf(now - 3L * ParkedBuys.DAY_MS)
        val parked = ParkedBuys(store, a, { day }, { clock[0] })
        val lamp = parked.park("Sample Lamp", 89_99, 2)
        clock[0] = now
        parked.buy(lamp, fun_)
        val (s, conn) = linked()
        // the store charges tax on top; a grocery charge the same day is left alone
        txns = listOf(
            SimpleFinMock.txn("C1", e(day), "-95.39", "SAMPLE HARDWARE 0012"),
            SimpleFinMock.txn("C2", e(day), "-12.00", "SAMPLE MARKET"),
        )
        val r = s.syncConnection(conn)
        assertEquals(2, r.outcome.newIds.size)
        val p = store.dao.parkedBuy(lamp)!!
        val charge = store.dao.transaction(p.transactionId!!)!!
        assertEquals(-95_39L, charge.amount)
        assertEquals(SortedBy.AUTO, charge.sortedBy)
        assertEquals(fun_, store.dao.linesOf(charge.id).single().envelopeId)
        assertEquals(-95_39L, a.snapshot().envelopeBalance(fun_))
        val other = r.outcome.newIds.first { it != charge.id }
        assertNull(store.dao.transaction(other)!!.sortedBy)
        assertEquals(0L, a.snapshot().sortedCheck)
    }
}
