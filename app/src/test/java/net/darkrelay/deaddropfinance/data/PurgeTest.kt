package net.darkrelay.deaddropfinance.data

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
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.ParkedBuys
import net.darkrelay.deaddropfinance.domain.Rules
import net.darkrelay.deaddropfinance.domain.Sorting
import net.darkrelay.deaddropfinance.sync.SyncSettings
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
 * Purge data: every kind of data, every saved secret and every setting goes, and the app opens at the setup wizard.
 * Erase everything removes the database and its keys. Synthetic data only.
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

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        store = BudgetStore(db) { now }
        a = BudgetActions(store)
        secrets = SecretStore(File(dir, "keys/${SecretStore.FILE_NAME}"), FakeWrapper())
        prefs = listOf("sync", "lock", "updates").map { ctx.getSharedPreferences("purge-$it", Context.MODE_PRIVATE).also { p -> p.edit(commit = true) { clear() } } }
        settings = SyncSettings(prefs[0])
        runBlocking { store.ensureSystemEnvelopes() }
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    /** An account with two transactions, entered by hand. */
    private suspend fun account() {
        val acct = a.saveAccount(null, "Sample Everyday", AccountType.BANK, 250_00, day.minusDays(30))
        for ((d, payee, amount) in listOf(Triple(day.minusDays(2), "SAMPLE MARKET 0042", -18_40L), Triple(day.minusDays(1), "SAMPLE CAFE", -7_25L))) {
            a.saveTransaction(null, BudgetActions.TransactionDraft(acct, d, payee, "", amount, listOf(BudgetActions.LineDraft(null, amount)), false))
        }
    }

    private fun purger() = Purger(db, secrets, prefs, listOf(receipts))

    /** The rows in every table, but the database's own. */
    private fun rowsLeft(): Map<String, Int> {
        val sql = db.openHelper.readableDatabase
        val names = mutableListOf<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) names += c.getString(0) }
        return names.filter { it !in Purger.KEPT && !it.startsWith("sqlite_") }.associateWith { t ->
            sql.query(if (t == "meta") "SELECT COUNT(*) FROM meta WHERE `key` != '${BudgetStore.META_DEVICE_ID}'" else "SELECT COUNT(*) FROM `$t`").use { c -> c.moveToFirst(); c.getInt(0) }
        }.filterValues { it > 0 }
    }

    @Test fun purgeRemovesEveryKindOfDataAndResetsSettings() = runBlocking<Unit> {
        account()
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
        settings.setupDone = true
        settings.alertSpending = false
        prefs[1].edit(commit = true) { putBoolean("lock_enabled", false); putInt("lock_timeout_minutes", 30) }
        prefs[2].edit(commit = true) { putBoolean("check_at_startup", false) }
        assertTrue(rowsLeft().toString(), rowsLeft().keys.containsAll(listOf("account", "envelope", "txn", "txn_line", "envelope_move", "bill", "rule", "subscription", "receipt", "parked_buy", "change_log", "meta", "notif_app")))

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
        assertTrue("every saved secret goes", secrets.names().isEmpty())
        // every setting is back to its default
        assertTrue(prefs.all { it.all.isEmpty() })
        assertFalse(settings.autoApprove)
        assertFalse(settings.setupDone)
        assertTrue(settings.alertSpending)
        assertEquals(1, ParkedBuys(store, a, { day }).defaultWait())
        assertTrue(store.deviceId().isNotBlank())
    }

    @Test fun eraseEverythingRemovesTheDatabaseAndTheSavedSecrets() = runBlocking<Unit> {
        val path = File(dir, "databases/budget.db").apply { parentFile!!.mkdirs() }
        val fileDb = Room.databaseBuilder(ctx, AppDatabase::class.java, path.path).allowMainThreadQueries().build()
        fileDb.dao().upsertAccount(AccountEntity(id = "a1", name = "Sample", type = AccountType.BANK, startDate = day, createdAt = 1, updatedAt = 1))
        secrets.put("receipts.key", "invented")
        fileDb.close()
        val left = Eraser(path, File(dir, "keys"), emptyList(), {}, prefs, {}, listOf(receipts)).erase()
        assertEquals(emptyList<String>(), left)
        assertFalse(path.exists())
        assertFalse(File(dir, "keys/${SecretStore.FILE_NAME}").exists())
        val reopened = Room.databaseBuilder(ctx, AppDatabase::class.java, path.path).allowMainThreadQueries().build()
        try { assertTrue(reopened.dao().accountsNow().isEmpty()) } finally { reopened.close() }
    }
}
