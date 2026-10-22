package net.darkrelay.deaddropfinance.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ConnectionEntity
import net.darkrelay.deaddropfinance.data.KeyWrapper
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.LinkedAccountEntity
import net.darkrelay.deaddropfinance.data.Provider
import net.darkrelay.deaddropfinance.data.SecretStore
import net.darkrelay.deaddropfinance.data.TransactionSource
import net.darkrelay.deaddropfinance.domain.BudgetActions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Long first syncs and the ways a sync can stop halfway, plus taking unlinked accounts off the budget. The SimpleFIN
 * mock filters a synthetic year of history by the requested dates, like the Bridge. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncResilienceTest {

    private class FakeWrapper : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x45).toByte() }
        override fun unwrap(blob: ByteArray) = blob.drop(1).map { (it.toInt() xor 0x45).toByte() }.toByteArray()
    }

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var actions: BudgetActions
    private lateinit var secrets: SecretStore
    private lateinit var settings: SyncSettings
    private val day = LocalDate.of(2030, 6, 30)
    private var now = day.atTime(12, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private val closers = mutableListOf<AutoCloseable>()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now }
        actions = BudgetActions(store)
        secrets = SecretStore(File(Files.createTempDirectory("ddf-res").toFile(), SecretStore.FILE_NAME), FakeWrapper())
        settings = SyncSettings(ctx.getSharedPreferences("res-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        runBlocking { store.ensureSystemEnvelopes() }
    }

    @After fun tearDown() { closers.forEach { it.close() }; db.close() }

    private fun service(limiterMax: Int = RequestLimiter.DEFAULT_MAX, sourceFor: ((ConnectionEntity) -> SyncSource)? = null) = SyncService(
        store, secrets, settings, clock = { now }, zone = ZoneOffset.UTC,
        httpFor = { Http(sleep = {}, connectTimeoutMs = 2000, readTimeoutMs = 3000, baseBackoffMs = 1) },
        allowLoopbackHttp = true, limiterMax = limiterMax, sourceFor = sourceFor,
    )

    /** A synthetic year: [count] transactions spread over the 365 days before [day]. */
    private fun year(count: Int = 400) = (0 until count).map { i ->
        val d = day.minusDays(i * 364L / count)
        Triple("Y$i", d, "-${1 + i % 50}.${(i % 100).toString().padStart(2, '0')}")
    }

    private fun param(q: String, name: String) = q.split("&").firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    /** Answers like the Bridge: only transactions posted in [start-date, end-date). */
    private fun bridge(m: SimpleFinMock, rows: List<Triple<String, LocalDate, String>>, delayMs: Long = 0) {
        m.accountsAnswer = { q ->
            val start = param(q, "start-date")?.toLong()
            val end = param(q, "end-date")?.toLong()
            val txns = if (start == null || end == null) emptyList() else rows.filter {
                val e = it.second.atTime(9, 0).toEpochSecond(ZoneOffset.UTC)
                e >= start && e < end
            }.map { SimpleFinMock.txn(it.first, it.second.atTime(9, 0).toEpochSecond(ZoneOffset.UTC), it.third, "SAMPLE STORE ${it.first}") }
            Answer(200, SimpleFinMock.accountSet("1000.00", txns), delayMs)
        }
    }

    private suspend fun connect(s: SyncService, m: SimpleFinMock, history: SyncService.History, target: SyncService.Target = SyncService.Target.New("Everyday")): String {
        val id = s.connectSimpleFin(m.setupToken())
        s.link(listOf(SyncService.LinkChoice(store.sync.linkedOf(id).single().id, target, history)))
        return id
    }

    private suspend fun synced() = store.dao.transactionsNow().filter { it.source == TransactionSource.SYNC }

    private fun windows(m: SimpleFinMock) = synchronized(m.queries) { m.queries.count { it.contains("start-date=") } }

    @Test fun aYearOfHistorySyncsWindowByWindowWithoutError() = runBlocking<Unit> {
        val m = SimpleFinMock().also { closers += it }
        val rows = year()
        bridge(m, rows)
        val s = service()
        val id = connect(s, m, SyncService.History.YEAR)
        val r = s.syncConnection(id)
        assertTrue(r.message, r.ok)
        assertEquals(rows.size, r.outcome.added)
        assertEquals(rows.size, synced().size)
        assertEquals("45-day windows over a year", 9, windows(m))
        assertEquals(rows.size, synced().map { it.externalId }.distinct().size)
        assertEquals(0, s.syncConnection(id).outcome.added)
        assertEquals(day, store.sync.linkedOf(id).single().lastSyncedDate)
    }

    @Test fun noRequestSpansMoreThan45DaysAndNinetyDaysTakesTwo() = runBlocking<Unit> {
        val m = SimpleFinMock().also { closers += it }
        val rows = year(count = 90)
        bridge(m, rows)
        val s = service()
        val id = connect(s, m, SyncService.History.DAYS_90)
        val r = s.syncConnection(id)
        assertTrue(r.message, r.ok)
        assertEquals("90 days of history in two requests", 2, windows(m))
        val spans = synchronized(m.queries) { m.queries.filter { it.contains("start-date=") } }
            .map { (param(it, "end-date")!!.toLong() - param(it, "start-date")!!.toLong()) / 86_400 }
        assertTrue("spans were $spans", spans.all { it <= 45 })
        // a later sync reaches back 5 days and is one request
        val before = windows(m)
        assertTrue(s.syncConnection(id).ok)
        assertEquals(1, windows(m) - before)
    }

    @Test fun theRequestCapMidHistoryStopsCleanlyAndTheNextSyncResumes() = runBlocking<Unit> {
        val m = SimpleFinMock().also { closers += it }
        val rows = year()
        bridge(m, rows)
        val s = service(limiterMax = 3)                       // the account list, then only 2 of the 9 windows
        val id = connect(s, m, SyncService.History.YEAR)
        val first = s.syncConnection(id)
        assertFalse(first.ok)
        assertTrue(first.message.contains("used up"))
        assertTrue(first.message.contains("is saved"))
        val partial = synced().size
        assertTrue("two windows landed", partial in 1 until rows.size)
        val stoppedAt = store.sync.linkedOf(id).single().lastSyncedDate!!
        assertTrue(stoppedAt.isBefore(day))
        assertTrue("the starting balance waits for the whole history", store.sync.linkedOf(id).single().setStartBalance)
        // a day later the budget frees up: it picks up at the last saved window, and nothing doubles (the 45-day windows
        // need a few days of this small budget to finish a year)
        now += RequestLimiter.DAY_MS + 60_000
        val before = synchronized(m.queries) { m.queries.size }
        var second = s.syncConnection(id)
        var days = 0
        while (!second.ok && days++ < 5) { now += RequestLimiter.DAY_MS + 60_000; second = s.syncConnection(id) }
        assertTrue(second.message, second.ok)
        val resumedFrom = param(synchronized(m.queries) { m.queries[before] }, "start-date")!!.toLong()
        assertTrue("resumes near where it stopped", resumedFrom >= stoppedAt.minusDays(10).atStartOfDay(ZoneOffset.UTC).toEpochSecond())
        assertEquals(rows.size, synced().size)
        assertEquals(rows.size, synced().map { it.externalId }.distinct().size)
        assertFalse(store.sync.linkedOf(id).single().setStartBalance)
    }

    @Test fun overlappingManualAndBackgroundSyncsMakeOneRun() = runBlocking<Unit> {
        val m = SimpleFinMock().also { closers += it }
        bridge(m, year(20), delayMs = 400)
        val s = service()
        val id = connect(s, m, SyncService.History.DAYS_30)
        val before = windows(m)
        val a = async { s.syncConnection(id, SyncService.Trigger.MANUAL) }
        val b = async { s.syncConnection(id, SyncService.Trigger.BACKGROUND) }
        val ra = a.await()
        val rb = b.await()
        assertTrue(ra.ok && rb.ok)
        assertEquals("one run, one request", 1, windows(m) - before)
        assertEquals(ra, rb)
    }

    @Test fun anExceptionInsideTheAdapterNeverEscapes() = runBlocking<Unit> {
        val broken = object : SyncSource {
            override val provider = Provider.SIMPLEFIN
            override val overlapDays = 5L
            override suspend fun accounts() = FetchResult(emptyList())
            override suspend fun fetch(wanted: Map<String, LocalDate>, to: LocalDate): FetchResult = throw IllegalStateException("made-up failure")
        }
        val s = service(sourceFor = { broken })
        val acct = actions.saveAccount(null, "Everyday", AccountType.BANK, 0, day)
        store.sync.upsertConnection(ConnectionEntity(id = "c1", provider = Provider.SIMPLEFIN, name = "SimpleFIN", createdAt = 1))
        store.sync.upsertLinked(LinkedAccountEntity(id = "l1", connectionId = "c1", providerAccountId = "ACT-1", providerName = "Everyday", accountId = acct, historyFrom = day.minusDays(10)))
        val r = s.syncConnection("c1")
        assertFalse(r.ok)
        assertTrue(r.message.contains("IllegalStateException"))
        assertFalse(r.message.contains("made-up failure"))
        assertEquals(r.message, store.sync.connection("c1")!!.lastResult)
    }

    @Test fun stopSyncingRemovesTheAccountFromTheBudgetByDefault() = runBlocking<Unit> {
        val m = SimpleFinMock().also { closers += it }
        m.accountsAnswer = { Answer(200, SimpleFinMock.accountSet("250.00")) }
        val s = service()
        val id = connect(s, m, SyncService.History.DAYS_30)
        s.syncConnection(id)
        val l = store.sync.linkedOf(id).single()
        assertEquals(250_00L, actions.snapshot().readyToAssign)
        s.setLink(l.id, null)                                  // remove from budget (the default)
        assertTrue(store.dao.account(l.accountId!!)!!.archived)
        assertEquals(0L, actions.snapshot().readyToAssign)
        actions.setAccountArchived(l.accountId!!, false)       // unarchive puts it back
        assertEquals(250_00L, actions.snapshot().readyToAssign)
    }

    @Test fun stopSyncingCanKeepTrackingByHand() = runBlocking<Unit> {
        val m = SimpleFinMock().also { closers += it }
        m.accountsAnswer = { Answer(200, SimpleFinMock.accountSet("-40.00")) }
        val s = service()
        val id = connect(s, m, SyncService.History.DAYS_30)
        s.syncConnection(id)
        val l = store.sync.linkedOf(id).single()
        assertEquals(-40_00L, actions.snapshot().readyToAssign)
        s.link(listOf(SyncService.LinkChoice(l.id, SyncService.Target.Ignore(removeFromBudget = false))))
        assertFalse(store.dao.account(l.accountId!!)!!.archived)
        assertEquals(-40_00L, actions.snapshot().readyToAssign)
    }

    @Test fun disconnectTakesTheAccountsOffTheBudgetUnlessKept() = runBlocking<Unit> {
        val m = SimpleFinMock().also { closers += it }
        m.accountsAnswer = { Answer(200, SimpleFinMock.accountSet("80.00")) }
        val s = service()
        val id = connect(s, m, SyncService.History.DAYS_30)
        s.syncConnection(id)
        val acct = store.sync.linkedOf(id).single().accountId!!
        s.disconnect(id)
        assertTrue(store.dao.account(acct)!!.archived)
        assertEquals(0L, actions.snapshot().readyToAssign)
    }

    @Test fun aTrackOnlyAccountSyncsButNothingLandsInToSort() = runBlocking<Unit> {
        val m = SimpleFinMock().also { closers += it }
        bridge(m, year(12))
        val s = service()
        val id = connect(s, m, SyncService.History.DAYS_30, SyncService.Target.New("Share", trackOnly = true))
        assertTrue(s.syncConnection(id).ok)
        val b = actions.snapshot()
        assertTrue(synced().isNotEmpty())
        assertTrue(b.toSort.isEmpty())
        assertEquals(0L, b.readyToAssign)
        assertTrue(store.dao.linesNow().all { it.bucket == LineBucket.OFF_BUDGET })
        assertEquals(1000_00L, s.postedBalance(store.sync.linkedOf(id).single().accountId!!))
    }
}
