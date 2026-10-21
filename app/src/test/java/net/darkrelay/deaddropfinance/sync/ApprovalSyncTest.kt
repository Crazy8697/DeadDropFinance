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
import net.darkrelay.deaddropfinance.data.SecretStore
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.Rules
import net.darkrelay.deaddropfinance.domain.Sorting
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
 * The approve flow through real syncs against the local SimpleFIN mock: an approved pending row stays approved when it
 * posts (same id, or re-issued with a new one), new rows come back for auto-approve and alerts, and auto-approve on a
 * sync happens only when it's turned on. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ApprovalSyncTest {

    private class FakeWrapper : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x21).toByte() }
        override fun unwrap(blob: ByteArray) = blob.drop(1).map { (it.toInt() xor 0x21).toByte() }.toByteArray()
    }

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var actions: BudgetActions
    private lateinit var sorting: Sorting
    private lateinit var secrets: SecretStore
    private lateinit var settings: SyncSettings
    private val day = LocalDate.of(2030, 9, 10)
    private val now = day.atTime(15, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private val closers = mutableListOf<AutoCloseable>()
    private var txns: List<String> = emptyList()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now }
        actions = BudgetActions(store)
        sorting = Sorting(store, actions)
        secrets = SecretStore(File(Files.createTempDirectory("ddf-appr").toFile(), SecretStore.FILE_NAME), FakeWrapper())
        settings = SyncSettings(ctx.getSharedPreferences("approval-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        runBlocking { store.ensureSystemEnvelopes() }
    }

    @After fun tearDown() { closers.forEach { it.close() }; db.close() }

    private fun e(d: LocalDate) = d.atTime(9, 0).toEpochSecond(ZoneOffset.UTC)

    /** A connected, linked account whose bank list is whatever [txns] holds at each sync. */
    private suspend fun linked(): Triple<SyncService, String, String> {
        val m = SimpleFinMock().also { closers += it }
        m.accountsAnswer = { q -> Answer(200, SimpleFinMock.accountSet("100.00", if (q.contains("start-date=")) txns else emptyList())) }
        val s = SyncService(
            store, secrets, settings, clock = { now }, zone = ZoneOffset.UTC,
            httpFor = { Http(sleep = {}, connectTimeoutMs = 2000, readTimeoutMs = 3000, baseBackoffMs = 1) }, allowLoopbackHttp = true,
        )
        val conn = s.connectSimpleFin(m.setupToken())
        val acct = actions.saveAccount(null, "Everyday", AccountType.BANK, 100_00, day.minusDays(20))
        s.link(listOf(SyncService.LinkChoice(store.sync.linkedOf(conn).single().id, SyncService.Target.Existing(acct), SyncService.History.DAYS_30)))
        return Triple(s, conn, acct)
    }

    @Test fun anApprovedPendingChargeStaysApprovedWhenItPosts() = runBlocking<Unit> {
        val food = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET))
        sorting.saveRule(null, Sorting.RuleDraft("sample market", splits = listOf(Rules.Split(food, 1))))
        val (s, conn, _) = linked()
        txns = listOf(SimpleFinMock.txn("P1", e(day.minusDays(1)), "-18.40", "SAMPLE MARKET 0042", pending = true))
        val r = s.syncConnection(conn)
        assertTrue(r.ok)
        assertEquals(1, r.outcome.newIds.size)
        val id = r.outcome.newIds.single()
        assertTrue(store.dao.transaction(id)!!.pending)
        assertTrue("pending can be approved", sorting.approve(id))
        // it posts under the same id, with the final amount
        txns = listOf(SimpleFinMock.txn("P1", e(day), "-19.10", "SAMPLE MARKET 0042"))
        val r2 = s.syncConnection(conn)
        assertTrue(r2.outcome.newIds.isEmpty())
        val t = store.dao.transaction(id)!!
        assertFalse(t.pending)
        assertEquals(SortedBy.APPROVED, t.sortedBy)
        assertEquals(food, store.dao.linesOf(id).single().envelopeId)
        assertEquals(-19_10L, store.dao.linesOf(id).single().amount)
        assertTrue(actions.snapshot().toSort.isEmpty())
    }

    @Test fun anApprovedPendingChargeReissuedUnderANewIdStaysApproved() = runBlocking<Unit> {
        val fun_ = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Fun", EnvelopeMode.FILL_TO_TARGET))
        val (s, conn, _) = linked()
        txns = listOf(SimpleFinMock.txn("P9", e(day.minusDays(2)), "-7.25", "SAMPLE ARCADE", pending = true))
        val id = s.syncConnection(conn).outcome.newIds.single()
        sorting.sortByHand(id, fun_)
        txns = listOf(SimpleFinMock.txn("Q9", e(day.minusDays(1)), "-7.25", "SAMPLE ARCADE"))
        val r = s.syncConnection(conn)
        assertTrue(r.outcome.newIds.isEmpty())
        val t = store.dao.transaction(id)!!
        assertFalse(t.pending)
        assertEquals(SortedBy.HAND, t.sortedBy)
        assertEquals(fun_, store.dao.linesOf(id).single().envelopeId)
    }

    @Test fun syncAutoApprovesOnlyWhenTurnedOn() = runBlocking<Unit> {
        val food = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET))
        sorting.saveRule(null, Sorting.RuleDraft("sample market", splits = listOf(Rules.Split(food, 1))))
        val (s, conn, _) = linked()
        assertFalse(settings.autoApprove)
        txns = listOf(SimpleFinMock.txn("A1", e(day.minusDays(3)), "-5.00", "SAMPLE MARKET"))
        val off = s.syncConnection(conn).outcome.newIds.single()
        assertEquals("off: it waits in To sort with its suggestion", null, store.dao.transaction(off)!!.sortedBy)
        settings.autoApprove = true
        txns = txns + SimpleFinMock.txn("A2", e(day.minusDays(1)), "-6.00", "SAMPLE MARKET") + SimpleFinMock.txn("A3", e(day.minusDays(1)), "-2.00", "SAMPLE ELSEWHERE")
        val r = s.syncConnection(conn)
        assertEquals(2, r.outcome.newIds.size)
        val byDesc = r.outcome.newIds.associateBy { store.dao.transaction(it)!!.importDescription }
        assertEquals(SortedBy.AUTO, store.dao.transaction(byDesc["SAMPLE MARKET"]!!)!!.sortedBy)
        assertEquals(null, store.dao.transaction(byDesc["SAMPLE ELSEWHERE"]!!)!!.sortedBy)
        assertEquals("the earlier one is left for its tap", null, store.dao.transaction(off)!!.sortedBy)
    }
}
