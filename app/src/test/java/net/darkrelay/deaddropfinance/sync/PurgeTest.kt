package net.darkrelay.deaddropfinance.sync

import android.app.Application
import android.content.Context
import androidx.core.content.edit
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
import net.darkrelay.deaddropfinance.data.Eraser
import net.darkrelay.deaddropfinance.data.KeyWrapper
import net.darkrelay.deaddropfinance.data.Purger
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.data.SecretStore
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.ParkedBuys
import net.darkrelay.deaddropfinance.domain.Rules
import net.darkrelay.deaddropfinance.domain.Sorting
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
 * Purge data: every kind of data and every setting goes, the SimpleFIN connection stays and syncs again (against
 * the local SimpleFIN mock), and the app opens at the setup wizard with bank sync connected. Erase everything still
 * removes the connection too. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PurgeTest {

    private class FakeWrapper : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x21).toByte() }
        override fun unwrap(blob: ByteArray) = blob.drop(1).map { (it.toInt() xor 0x21).toByte() }.toByteArray()
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var a: BudgetActions
    private lateinit var secrets: SecretStore
    private lateinit var settings: SyncSettings
    private lateinit var prefs: List<android.content.SharedPreferences>
    private val dir = Files.createTempDirectory("ddf-purge").toFile()
    private val receipts = File(dir, "receipts")
    private val day = LocalDate.of(2030, 8, 12)
    private val now = day.atTime(15, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private val closers = mutableListOf<AutoCloseable>()
    private var txns: List<String> = emptyList()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        store = BudgetStore(db) { now }
        a = BudgetActions(store)
        secrets = SecretStore(File(dir, "keys/${SecretStore.FILE_NAME}"), FakeWrapper())
        prefs = listOf("sync", "lock", "updates").map { ctx.getSharedPreferences("purge-$it", Context.MODE_PRIVATE).also { p -> p.edit(commit = true) { clear() } } }
        settings = SyncSettings(prefs[0])
        runBlocking { store.ensureSystemEnvelopes() }
    }

    @After fun tearDown() { closers.forEach { it.close() }; db.close(); dir.deleteRecursively() }

    private fun e(d: LocalDate) = d.atTime(9, 0).toEpochSecond(ZoneOffset.UTC)

    private fun service() = SyncService(
        store, secrets, settings, clock = { now }, zone = ZoneOffset.UTC,
        httpFor = { Http(sleep = {}, connectTimeoutMs = 2000, readTimeoutMs = 3000, baseBackoffMs = 1) }, allowLoopbackHttp = true,
    )

    /** A SimpleFIN connection (the local mock) linked to a new account, synced once. */
    private suspend fun connected(): Pair<SyncService, String> {
        val m = SimpleFinMock().also { closers += it }
        m.accountsAnswer = { q -> Answer(200, SimpleFinMock.accountSet("250.00", if (q.contains("start-date=")) txns else emptyList())) }
        val s = service()
        val conn = s.connectSimpleFin(m.setupToken())
        txns = listOf(SimpleFinMock.txn("T1", e(day.minusDays(2)), "-18.40", "SAMPLE MARKET 0042"), SimpleFinMock.txn("T2", e(day.minusDays(1)), "-7.25", "SAMPLE CAFE"))
        s.link(listOf(SyncService.LinkChoice(store.sync.linkedOf(conn).single().id, SyncService.Target.New("Sample Everyday", AccountType.BANK), SyncService.History.DAYS_30)))
        assertTrue(s.syncConnection(conn).ok)
        return s to conn
    }

    private fun purger() = Purger(db, secrets, prefs, listOf(receipts))

    /** The rows in every table, but the connection's and the database's own. */
    private fun rowsLeft(): Map<String, Int> {
        val sql = db.openHelper.readableDatabase
        val names = mutableListOf<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) names += c.getString(0) }
        return names.filter { it !in Purger.KEPT && !it.startsWith("sqlite_") }.associateWith { t ->
            sql.query(if (t == "meta") "SELECT COUNT(*) FROM meta WHERE `key` != '${BudgetStore.META_DEVICE_ID}'" else "SELECT COUNT(*) FROM `$t`").use { c -> c.moveToFirst(); c.getInt(0) }
        }.filterValues { it > 0 }
    }

    @Test fun purgeRemovesEveryKindOfDataAndResetsSettings() = runBlocking<Unit> {
        val (_, conn) = connected()
        // one of everything
        val food = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Groceries", EnvelopeMode.FILL_TO_TARGET, target = 300_00, everyday = true))
        val tx = store.dao.transactionsNow().first().id
        a.sortInto(tx, food)
        a.move(null, food, 50_00)
        a.saveBonus(null, 20_00, day, "Venmo", "Sample")
        store.ensureCategories()
        store.setBudgetStart(day.minusDays(3))
        assertTrue(store.dao.transactionsNow().any { it.kind == net.darkrelay.deaddropfinance.data.TransactionKind.BONUS })
        val bills = BillActions(store, a) { day }
        val bill = bills.saveBill(null, BillActions.BillDraft("Sample Power", 80_00, day.plusDays(5), envelopeId = food, reminder = true))
        bills.correctPayday(day.plusDays(9))
        Sorting(store, a).saveRule(null, Sorting.RuleDraft("sample market", splits = listOf(Rules.Split(food, 1))))
        store.write { put(null, net.darkrelay.deaddropfinance.data.SubscriptionEntity(name = "Sample Stream", matchKey = "sample stream", billId = bill, createdAt = now, updatedAt = now)) }
        val rid = store.write { put(null, ReceiptEntity(transactionId = tx, store = "Sample Market", total = 18_40, pages = 1, createdAt = now, updatedAt = now)).id }
        val images = ReceiptImages(receipts) { ReceiptImages.keyFrom(secrets) }
        images.write(rid, 0, "invented photo".toByteArray())
        ParkedBuys(store, a, { day }) { now }.park("Sample Lamp", 24_00, 1)
        ParkedBuys(store, a, { day }) { now }.setDefaultWait(7)
        bills.pickApp("com.example.samplebank", "Sample Bank", store.dao.accountsNow().first().id)
        settings.autoApprove = true
        settings.intervalHours = 24
        settings.setupDone = true
        settings.alertSpending = false
        prefs[1].edit(commit = true) { putBoolean("lock_enabled", false); putInt("lock_timeout_minutes", 30) }
        prefs[2].edit(commit = true) { putBoolean("check_at_startup", false) }
        assertTrue(rowsLeft().toString(), rowsLeft().keys.containsAll(listOf("account", "envelope", "txn", "txn_line", "envelope_move", "bill", "rule", "subscription", "receipt", "parked_buy", "change_log", "meta", "notif_app", "sync_log")))

        purger().purge()

        assertEquals("every budget table is empty", emptyMap<String, Int>(), rowsLeft())
        assertNull("the budget start is reset; it is set again when setup finishes", store.budgetStart())
        // categories start over: the default set and Unplanned come back the next time the app starts
        store.ensureCategories()
        val fresh = a.snapshot()
        assertEquals(17, fresh.categories.size)
        assertEquals("Unplanned", fresh.unplannedEnvelope!!.name)
        assertTrue("no claims", fresh.categories.all { it.envelopeId == null })
        assertTrue("receipt photos gone", receipts.listFiles().isNullOrEmpty())
        assertEquals(setOf(SecretStore.simplefinUrl(conn)), secrets.names())
        // every setting is back to its default
        assertTrue(prefs.all { it.all.isEmpty() })
        assertFalse(settings.autoApprove)
        assertEquals(SyncSettings.DEFAULT_INTERVAL_HOURS, settings.intervalHours)
        assertFalse(settings.setupDone)
        assertTrue(settings.alertSpending)
        assertEquals(1, ParkedBuys(store, a, { day }).defaultWait())
        // the connection and its bank account stay, linked to nothing
        assertEquals(listOf(conn), store.sync.connectionsNow().map { it.id })
        val l = store.sync.linkedOf(conn).single()
        assertNull(l.accountId)
        assertNull(l.lastSyncedDate)
        assertTrue(store.deviceId().isNotBlank())
    }


    @Test fun theConnectionSurvivesAndAMockedSyncWorksAfterwards() = runBlocking<Unit> {
        val (s, conn) = connected()
        assertEquals(2, store.dao.transactionsNow().size)
        purger().purge()
        assertTrue(store.dao.transactionsNow().isEmpty())
        // the wizard's Link step, then a sync: no new setup token
        txns = txns + SimpleFinMock.txn("T3", e(day), "-3.10", "SAMPLE KIOSK")
        s.link(listOf(SyncService.LinkChoice(store.sync.linkedOf(conn).single().id, SyncService.Target.New("Sample Everyday", AccountType.BANK), SyncService.History.DAYS_30)))
        val r = s.syncConnection(conn)
        assertTrue(r.message, r.ok)
        assertEquals(3, store.dao.transactionsNow().size)
        assertEquals(listOf("Sample Everyday"), store.dao.accountsNow().map { it.name })
        assertEquals(0L, a.snapshot().sortedCheck)
    }

    @Test fun eraseEverythingStillRemovesTheSimpleFinConnection() = runBlocking<Unit> {
        val path = File(dir, "databases/budget.db").apply { parentFile!!.mkdirs() }
        val fileDb = Room.databaseBuilder(ctx, AppDatabase::class.java, path.path).allowMainThreadQueries().build()
        fileDb.syncDao().upsertConnection(net.darkrelay.deaddropfinance.data.ConnectionEntity(id = "c1", provider = net.darkrelay.deaddropfinance.data.Provider.SIMPLEFIN, name = "Sample Bridge", createdAt = 1))
        secrets.put(SecretStore.simplefinUrl("c1"), "invented")
        fileDb.close()
        val left = Eraser(path, File(dir, "keys"), emptyList(), {}, prefs, {}, listOf(receipts)).erase()
        assertEquals(emptyList<String>(), left)
        assertFalse(path.exists())
        assertFalse(File(dir, "keys/${SecretStore.FILE_NAME}").exists())
        val reopened = Room.databaseBuilder(ctx, AppDatabase::class.java, path.path).allowMainThreadQueries().build()
        try { assertTrue(reopened.syncDao().connectionsNow().isEmpty()) } finally { reopened.close() }
    }
}
