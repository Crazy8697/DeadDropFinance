package net.darkrelay.deaddropfinance.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Delete account and Erase everything. Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeleteAndEraseTest {

    private val d = LocalDate.of(2030, 5, 1)
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun <T> withStore(block: suspend (BudgetStore, BudgetActions) -> T): T {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val store = BudgetStore(db)
            return runBlocking { store.ensureSystemEnvelopes(); block(store, BudgetActions(store)) }
        } finally {
            db.close()
        }
    }

    @Test fun deletingAnAccountTakesItsTransactionsAndSplitsWithIt() = withStore { store, a ->
        val chk = a.saveAccount(null, "Checking", AccountType.BANK, 100_00, d)
        val sav = a.saveAccount(null, "Savings", AccountType.BANK, 300_00, d)
        val food = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET))
        val fun_ = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Fun", EnvelopeMode.FILL_TO_TARGET))
        a.assign(food, 50_00)
        a.assign(fun_, 50_00)
        // a split spend from savings across two envelopes, and an income line into Ready to Assign
        a.saveTransaction(null, TransactionDraft(sav, d.plusDays(1), "Sample Store", "", -30_00, listOf(LineDraft(food, -20_00), LineDraft(fun_, -10_00))))
        a.saveTransaction(null, TransactionDraft(sav, d.plusDays(2), "Sample Refund", "", 15_00, listOf(LineDraft(null, 15_00, toReadyToAssign = true))))
        val before = a.snapshot()
        assertEquals(100_00L + 300_00 - 100_00 + 15_00, before.readyToAssign)
        assertEquals(30_00L, before.envelopeBalance(food))
        assertEquals(2, a.deleteAccountCount(sav))
        a.deleteAccount(sav)
        val after = a.snapshot()
        assertTrue(after.accounts.none { it.id == sav })
        assertEquals("envelopes get back what the deleted spend took", 50_00L, after.envelopeBalance(food))
        assertEquals(50_00L, after.envelopeBalance(fun_))
        assertEquals("Ready to Assign loses the account's start and income", 100_00L - 100_00, after.readyToAssign)
        assertEquals(0L, after.sortedCheck)
        // tombstones in the change log, as every delete
        assertTrue(store.dao.changes().any { it.entityType == "account" && it.entityId == sav && it.operation == ChangeOperation.DELETE })
        assertEquals(chk, after.accounts.single().id)
    }

    @Test fun aTransferKeepsTheOtherSideInToSort() = withStore { store, a ->
        val chk = a.saveAccount(null, "Checking", AccountType.BANK, 100_00, d)
        val sav = a.saveAccount(null, "Savings", AccountType.BANK, 300_00, d)
        a.saveTransfer(null, sav, chk, 40_00, d.plusDays(1), "")          // savings sent 40 to checking
        a.saveTransfer(null, chk, sav, 15_00, d.plusDays(2), "")          // checking sent 15 to savings
        assertEquals(125_00L, a.snapshot().accountBalance(chk))
        a.deleteAccount(sav)
        val b = a.snapshot()
        assertEquals("checking keeps its balance", 125_00L, b.accountBalance(chk))
        assertEquals(2, b.toSort.size)
        assertEquals(setOf(40_00L, -15_00L), b.toSort.map { it.amount }.toSet())
        assertTrue(b.toSort.all { it.kind == TransactionKind.NORMAL && it.accountId == chk })
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun deletingAnAccountRemovesItsBankLink() = withStore { store, a ->
        val acct = a.saveAccount(null, "Linked", AccountType.BANK, 0, d)
        store.sync.upsertConnection(ConnectionEntity(id = "c1", provider = Provider.SIMPLEFIN, name = "SimpleFIN", createdAt = 1))
        store.sync.upsertLinked(LinkedAccountEntity(id = "l1", connectionId = "c1", providerAccountId = "ACT", providerName = "Sample", accountId = acct, lastSyncedDate = d))
        a.deleteAccount(acct)
        val l = store.sync.linkedNow().single()
        assertNull(l.accountId)
        assertNull(l.lastSyncedDate)
    }

    private class FakeWrapper : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain
        override fun unwrap(blob: ByteArray) = blob.drop(1).toByteArray()
    }

    @Test fun eraseEverythingLeavesAFreshInstall() {
        val dir = Files.createTempDirectory("ddf-erase").toFile()
        val dbFile = File(dir, "databases/budget.db").apply { parentFile!!.mkdirs() }
        val keys = File(dir, "files/keys")
        // a "used" install: database files, a wrapped key, saved credentials, settings, scheduled work, Keystore keys
        listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).writeText("x") }
        val wrapper = FakeWrapper()
        assertTrue(DbKeys(keys, wrapper, SecureRandom()).loadOrCreate(databaseExists = false) is DbKeys.Result.Ok)
        SecretStore(File(keys, SecretStore.FILE_NAME), wrapper).put("simplefin.url.c1", "made-up")
        val prefs = listOf("lock", "sync", "updates").map { ctx.getSharedPreferences("erase-$it", Context.MODE_PRIVATE).apply { edit().putBoolean("k", true).commit() } }
        val keystore = mutableSetOf(KeystoreWrapper.ALIAS, "secrets-alias")
        var workCancelled = false

        val left = Eraser(dbFile, keys, listOf(KeystoreWrapper.ALIAS, "secrets-alias"), { keystore.remove(it) }, prefs, { workCancelled = true }).erase()

        assertEquals(emptyList<String>(), left)
        assertFalse(dbFile.exists() || File(dbFile.path + "-wal").exists() || File(dbFile.path + "-shm").exists())
        assertFalse(keys.exists())
        assertTrue("both Keystore keys deleted", keystore.isEmpty())
        assertTrue(prefs.all { it.all.isEmpty() })
        assertTrue(workCancelled)
        // and a new start works like a fresh install: a new key is made and a new database opens cleanly
        assertTrue(DbKeys(keys, wrapper, SecureRandom()).loadOrCreate(databaseExists = dbFile.exists()) is DbKeys.Result.Ok)
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbFile.path).addMigrations(*Migrations.ALL).build()
        try {
            runBlocking {
                val store = BudgetStore(db)
                store.ensureSystemEnvelopes()
                assertTrue(store.dao.accountsNow().isEmpty())
                assertEquals(1, store.dao.envelopesNow().size)
                assertTrue(store.sync.connectionsNow().isEmpty())
            }
        } finally {
            db.close()
        }
    }
}
