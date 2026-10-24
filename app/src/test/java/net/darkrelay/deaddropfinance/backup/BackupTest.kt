package net.darkrelay.deaddropfinance.backup

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ConnectionEntity
import net.darkrelay.deaddropfinance.data.LinkedAccountEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.Provider
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.ParkedBuys
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backup and restore: a backup restored gives back the same budget, receipt photos and settings, on this phone or a
 * new one; a wrong password, a file that isn't a backup, a changed byte or a file cut short fail with a plain reason
 * and change nothing. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val day = LocalDate.of(2030, 7, 1)
    private val rounds = 1_000
    private val key = ByteArray(32) { (it * 7).toByte() }
    private val closers = mutableListOf<AppDatabase>()
    private val tmp = Files.createTempDirectory("ddf-backup").toFile()
    private val photo1 = ByteArray(150_000) { (it % 251).toByte() }
    private val photo2 = "sample page two".toByteArray()

    private inner class Phone(name: String) {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build().also { closers += it }
        val store = BudgetStore(db)
        val actions = BudgetActions(store)
        val images = ReceiptImages(File(tmp, "$name-receipts"), { key })
        val prefs = listOf("sync", "lock", "updates").associateWith { ctx.getSharedPreferences("$name-$it", Context.MODE_PRIVATE).also { p -> p.edit(commit = true) { clear() } } }
        val backups = Backups(db, images, File(tmp, "$name-staging"), SettingsTransfer(prefs), "0.0.0-test")

        /** Every carried table's rows, as text, for comparing. */
        fun dump(): Map<String, List<String>> {
            val sql = db.openHelper.readableDatabase
            val out = sortedMapOf<String, List<String>>()
            val names = mutableListOf<String>()
            sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) names += c.getString(0) }
            for (t in names.filter { it !in Backups.LEFT_OUT && !it.startsWith("sqlite_") }) {
                val rows = mutableListOf<String>()
                sql.query("SELECT * FROM `$t`").use { c ->
                    while (c.moveToNext()) rows += (0 until c.columnCount).joinToString("|") { i -> "${c.getColumnName(i)}=${if (c.isNull(i)) "null" else c.getString(i)}" }
                }
                out[t] = rows.filterNot { t == "meta" && it.startsWith("key=${BudgetStore.META_DEVICE_ID}|") }.sorted()
            }
            return out
        }
    }

    @Before fun setUp() { tmp.mkdirs() }
    @After fun tearDown() { closers.forEach { it.close() }; tmp.deleteRecursively() }

    /** A small budget with one of everything, two receipt photos and a changed setting. */
    private fun Phone.fill() = runBlocking {
        store.ensureSystemEnvelopes()
        val acct = actions.saveAccount(null, "Sample Checking", AccountType.BANK, 800_00, day.minusDays(30))
        val food = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Groceries", EnvelopeMode.FILL_TO_TARGET, target = 300_00, everyday = true))
        actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, debtOwed = 1200_00, requiredPayment = 60_00, dueDate = day.plusDays(9)))
        actions.move(null, food, 250_00)
        store.ensureCategories()
        store.setBudgetStart(day.minusDays(7))
        actions.saveAccount(acct, "Sample Checking", AccountType.BANK, 800_00, day.minusDays(30), showInActivity = false)
        // the wrong-sign Adjust (1.2.2): the bank says the app's balance with the other sign
        actions.matchStartingBalanceToBank(acct, -actions.snapshot().postedBalanceOn(acct, day), day)
        net.darkrelay.deaddropfinance.domain.CategoryActions(store).setClaims(food, setOf(actions.snapshot().categories.first { it.name == "Groceries" }.id))
        val tx = store.write {
            val t = put(null, TransactionEntity(accountId = acct, date = day, payee = "SAMPLE MARKET", amount = -42_10, createdAt = now, updatedAt = now))
            put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.ENVELOPE, envelopeId = food, amount = -42_10, createdAt = now, updatedAt = now))
            t.id
        }
        val rid = store.write {
            val r = put(null, ReceiptEntity(transactionId = tx, store = "Sample Market", total = 42_10, pages = 2, createdAt = now, updatedAt = now))
            put(null, ReceiptItemEntity(receiptId = r.id, text = "SAMPLE BREAD", name = "sample bread", amount = 3_10, createdAt = now, updatedAt = now))
            r.id
        }
        images.write(rid, 0, photo1)
        images.write(rid, 1, photo2)
        BillActions(store, actions) { day }.saveBill(null, BillActions.BillDraft("Sample Power", 90_00, day.plusDays(12), envelopeId = food, reminder = true))
        BillActions(store, actions) { day }.correctPayday(day.plusDays(14))
        ParkedBuys(store, actions, { day }).park("Sample Lamp", 24_00, 2)
        actions.saveBonus(null, 30_00, day, "Gift card", "Sample gift")
        prefs.getValue("sync").edit(commit = true) { putBoolean("auto_approve", true); putInt("interval_hours", 12) }
        prefs.getValue("lock").edit(commit = true) { putInt("lock_timeout_minutes", 15) }
        rid
    }

    private fun Phone.backup(passphrase: String): ByteArray {
        val out = ByteArrayOutputStream()
        backups.write(out, passphrase.toCharArray(), rounds)
        return out.toByteArray()
    }

    @Test fun backupThenRestoreGivesBackTheSameDataPhotosAndSettings() = runBlocking<Unit> {
        val a = Phone("a")
        val rid = a.fill()
        val before = a.dump()
        val file = a.backup("sample backup words")
        assertTrue("the budget isn't readable in the file", !String(file, Charsets.ISO_8859_1).contains("SAMPLE MARKET"))
        // things change after the backup
        a.actions.saveAccount(null, "Sample Savings", AccountType.BANK, 5_00, day)
        a.images.write(rid, 0, "changed".toByteArray())
        a.images.write("other-receipt", 0, "stray".toByteArray())
        a.prefs.getValue("sync").edit(commit = true) { putBoolean("auto_approve", false) }
        assertNotEquals(before, a.dump())
        val s = a.backups.restore(ByteArrayInputStream(file), "sample backup words".toCharArray())
        assertEquals(before, a.dump())
        assertEquals(1, s.accounts)
        assertEquals(2, s.photos)
        assertArrayEquals(photo1, a.images.read(rid, 0))
        assertArrayEquals(photo2, a.images.read(rid, 1))
        assertEquals(listOf(rid to 0, rid to 1), a.images.stored())
        assertEquals(true, a.prefs.getValue("sync").getBoolean("auto_approve", false))
        assertEquals(15, a.prefs.getValue("lock").getInt("lock_timeout_minutes", 5))
        // and onto a new phone, which keeps its own device id
        val b = Phone("b")
        runBlocking { b.store.ensureSystemEnvelopes() }
        val bDevice = b.store.deviceId()
        b.backups.restore(ByteArrayInputStream(file), "sample backup words".toCharArray())
        assertEquals(before, b.dump())
        assertEquals(bDevice, b.store.deviceId())
        assertArrayEquals(photo1, b.images.read(rid, 0))
        assertEquals(12, b.prefs.getValue("sync").getInt("interval_hours", 6))
        val budget = b.actions.snapshot()
        assertEquals(0L, budget.sortedCheck)
        assertEquals("bonus income comes back", 30_00L, budget.bonusTotal)
        val food = budget.envelopes.first { it.name == "Sample Groceries" }.id
        assertEquals("categories, the claim and Unplanned come back", listOf("Groceries"), budget.claimsOf(food).map { it.name })
        assertEquals(17, budget.categories.size)
        assertEquals("Unplanned", budget.unplannedEnvelope!!.name)
        assertEquals("the budget start date comes back", day.minusDays(7), b.store.budgetStart())
        assertEquals("Show in Activity comes back", false, budget.accounts.single().showInActivity)
        assertEquals("the adjusted starting balance comes back", -800_00L, budget.accounts.single().startingBalance)
        assertEquals(250_00L - 42_10, budget.envelopeBalance(budget.envelopes.first { it.name == "Sample Groceries" }.id))
    }

    @Test fun theBankConnectionStaysOutOfTheBackupAndRestoreLeavesThePhonesOwn() = runBlocking<Unit> {
        val a = Phone("c")
        a.fill()
        a.db.syncDao().upsertConnection(ConnectionEntity(id = "conn-a", provider = Provider.SIMPLEFIN, name = "Sample Bridge", createdAt = 1))
        val file = a.backup("sample words")
        val b = Phone("d")
        b.db.syncDao().upsertConnection(ConnectionEntity(id = "conn-b", provider = Provider.SIMPLEFIN, name = "Other Sample Bridge", createdAt = 1))
        b.backups.restore(ByteArrayInputStream(file), "sample words".toCharArray())
        assertEquals(listOf("conn-b"), b.db.syncDao().connectionsNow().map { it.id })
        // the bank balance sign choice is the phone's own, on its bank link: a backup and restore keeps it there
        a.db.syncDao().upsertLinked(LinkedAccountEntity(id = "link-a", connectionId = "conn-a", providerAccountId = "ACT", providerName = "Sample", providerBalance = 41_07, balanceSignFlipped = true))
        val again = a.backup("sample words")
        a.backups.restore(ByteArrayInputStream(again), "sample words".toCharArray())
        val kept = a.db.syncDao().linkedNow().single()
        assertEquals(true, kept.balanceSignFlipped)
        assertEquals(-41_07L, kept.bankBalance)
    }

    @Test fun aWrongPasswordOrABadFileFailsCleanlyAndChangesNothing() = runBlocking<Unit> {
        val a = Phone("e")
        val rid = a.fill()
        val file = a.backup("right words")
        val b = Phone("f")
        b.fill()
        b.images.write(rid, 0, "phone b's own".toByteArray())
        val before = b.dump()
        val photoBefore = b.images.read(rid, 0)
        fun refused(bytes: ByteArray, passphrase: String, why: String) {
            try {
                b.backups.restore(ByteArrayInputStream(bytes), passphrase.toCharArray())
                fail("restored from $why")
            } catch (e: BackupException) {
                assertTrue(e.message!!.isNotBlank())
            }
            assertEquals("nothing changed after $why", before, b.dump())
            assertArrayEquals(photoBefore, b.images.read(rid, 0))
            assertEquals(true, b.prefs.getValue("sync").getBoolean("auto_approve", false))
            assertTrue(!File(tmp, "f-staging").exists())
        }
        refused(file, "wrong words", "a wrong password")
        refused("Date,Description,Amount\n2030-01-01,SAMPLE,1.00\n".toByteArray(), "right words", "a CSV file")
        refused(ByteArray(0), "right words", "an empty file")
        refused(file.copyOf(file.size - 200), "right words", "a file cut short")
        refused(file.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x40).toByte() }, "right words", "a changed byte")
        refused(file + byteArrayOf(1, 2, 3), "right words", "bytes added at the end")
        // the messages say what's wrong in plain words
        try { b.backups.restore(ByteArrayInputStream(file), "wrong words".toCharArray()); fail() } catch (e: BackupException) {
            assertEquals("Wrong password, or the backup file is damaged.", e.message)
        }
        try { b.backups.restore(ByteArrayInputStream("hello".toByteArray()), "x".toCharArray()); fail() } catch (e: BackupException) {
            assertEquals("That isn't a Dead Drop Finance backup file.", e.message)
        }
    }

    @Test fun theFileNameIsDated() {
        assertEquals("deaddrop-2030-07-01.ddfbak", Backups.fileName(day))
    }
}
