package net.darkrelay.deaddropfinance.sync

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ConnectionEntity
import net.darkrelay.deaddropfinance.data.KeyWrapper
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.LinkedAccountEntity
import net.darkrelay.deaddropfinance.data.Provider
import net.darkrelay.deaddropfinance.data.SecretStore
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
 * The 0.7.1 upgrade cleanup: a 0.7.0 install's Teller connection is removed the way Disconnect with "keep" works.
 * The old rows are written with plain SQL, as 0.7.0 stored them. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RemovedProviderCleanupTest {

    private class FakeWrapper : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x21).toByte() }
        override fun unwrap(blob: ByteArray) = blob.drop(1).map { (it.toInt() xor 0x21).toByte() }.toByteArray()
    }

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var secrets: SecretStore
    private lateinit var settings: SyncSettings
    private lateinit var prefs: SharedPreferences
    private val rescheduled = mutableListOf<Boolean>()
    private val d = LocalDate.of(2030, 4, 1)

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { 1_000L }
        secrets = SecretStore(File(Files.createTempDirectory("ddf-cleanup").toFile(), SecretStore.FILE_NAME), FakeWrapper())
        prefs = ctx.getSharedPreferences("cleanup-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        settings = SyncSettings(prefs)
    }

    @After fun tearDown() = db.close()

    private fun cleanup() = RemovedProviderCleanup(store, secrets, settings) { rescheduled += it }

    /** A 0.7.0-style Teller connection with one linked account and one synced transaction. Returns the transaction id. */
    private suspend fun oldConnection(): String {
        val acct = store.write { put(null, AccountEntity(id = "a1", name = "Sample", type = AccountType.BANK, startDate = d, createdAt = now, updatedAt = now)).id }
        db.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO connection (id, provider, name, enrollment_id, status, created_at) VALUES ('old1', 'TELLER', 'Sample Bank', 'enr_x', 'OK', 1)")
            execSQL("INSERT INTO linked_account (id, connection_id, provider_account_id, provider_name, mask, account_id, set_start_balance) VALUES ('l1', 'old1', 'acc_x', 'Sample Checking', '0001', 'a1', 0)")
            execSQL("INSERT INTO sync_log (id, connection_id, at, ok, added, updated, removed, message) VALUES ('g1', 'old1', 1, 1, 1, 0, 0, 'ok')")
        }
        val tx = store.write {
            val t = put(
                null,
                TransactionEntity(
                    accountId = acct, date = d, payee = "SAMPLE SHOP", amount = -5_00, source = TransactionSource.SYNC,
                    importDescription = "SAMPLE SHOP", externalId = "teller:acc_x:t1", createdAt = now, updatedAt = now,
                ),
            )
            put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = -5_00, createdAt = now, updatedAt = now))
            t.id
        }
        secrets.put("teller.token.old1", "made-up-token")
        secrets.put("teller.certificate", "made-up-certificate")
        secrets.put("teller.private_key", "made-up-key")
        prefs.edit().putString("teller_app_id", "app_madeup").putString("teller_environment", "development").putLong("attempt.old1", 5).commit()
        return tx
    }

    /** A SimpleFIN connection that must be left exactly as it is. */
    private suspend fun simplefinConnection(): String {
        store.sync.upsertConnection(ConnectionEntity(id = "sf1", provider = Provider.SIMPLEFIN, name = "SimpleFIN", createdAt = 2))
        store.sync.upsertLinked(LinkedAccountEntity(id = "l2", connectionId = "sf1", providerAccountId = "ACT-1", providerName = "Everyday", accountId = null))
        secrets.put(SecretStore.simplefinUrl("sf1"), "made-up-access")
        return store.write {
            put(null, TransactionEntity(accountId = "a2", date = d, payee = "OTHER", amount = -1_00, externalId = "simplefin:ACT-1:x1", createdAt = now, updatedAt = now)).id
        }
    }

    @Test fun aTellerConnectionIsRemovedAndItsTransactionsKept() = runBlocking<Unit> {
        val tx = oldConnection()
        val changesBefore = store.dao.changes().size
        val r = cleanup().run()
        assertEquals(RemovedProviderCleanup.Result(connections = 1, transactions = 1, secrets = 3), r)
        assertTrue(store.sync.connectionsNow().isEmpty())
        assertTrue(store.sync.linkedNow().isEmpty())
        assertNull(store.sync.lastLog("old1"))
        assertTrue(secrets.names().none { it.startsWith("teller.") })
        val kept = store.dao.transaction(tx)!!
        assertFalse(kept.deleted)
        assertNull(kept.externalId)
        assertEquals(1, store.dao.linesOf(tx).size)
        assertEquals("Sample", store.dao.account("a1")!!.name)
        assertEquals(listOf(false), rescheduled)                  // no connection left: background sync is unscheduled
        assertFalse(prefs.contains("teller_app_id") || prefs.contains("teller_environment") || prefs.contains("attempt.old1"))
        // only the transaction edit is change-logged; the connection tables never are
        val added = store.dao.changes().drop(changesBefore)
        assertEquals(listOf("transaction"), added.map { it.entityType })
    }

    @Test fun onlySimpleFinIsLeftUntouched() = runBlocking<Unit> {
        val tx = simplefinConnection()
        val changesBefore = store.dao.changes().size
        val r = cleanup().run()
        assertFalse(r.changed)
        assertEquals(listOf("sf1"), store.sync.connectionsNow().map { it.id })
        assertEquals(1, store.sync.linkedNow().size)
        assertEquals("made-up-access", secrets.get(SecretStore.simplefinUrl("sf1")))
        assertEquals("simplefin:ACT-1:x1", store.dao.transaction(tx)!!.externalId)
        assertEquals(changesBefore, store.dao.changes().size)
        assertTrue(rescheduled.isEmpty())
    }

    @Test fun bothTogetherKeepSimpleFinAndKeepTheJob() = runBlocking<Unit> {
        oldConnection()
        simplefinConnection()
        cleanup().run()
        assertEquals(listOf("sf1"), store.sync.connectionsNow().map { it.id })
        assertEquals(setOf(SecretStore.simplefinUrl("sf1")), secrets.names())
        assertEquals(listOf(true), rescheduled)
    }

    @Test fun runningItTwiceChangesNothingTheSecondTime() = runBlocking<Unit> {
        oldConnection()
        cleanup().run()
        val changes = store.dao.changes().size
        val second = cleanup().run()
        assertFalse(second.changed)
        assertEquals(changes, store.dao.changes().size)
        assertEquals(listOf(false), rescheduled)
    }

    @Test fun aFreshInstallHasNothingToDo() = runBlocking<Unit> {
        assertFalse(cleanup().run().changed)
        assertTrue(rescheduled.isEmpty())
    }
}
