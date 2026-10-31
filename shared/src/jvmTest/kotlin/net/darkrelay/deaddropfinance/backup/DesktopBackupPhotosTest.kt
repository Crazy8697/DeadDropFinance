package net.darkrelay.deaddropfinance.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.domain.BudgetActions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Receipt photos in the program's backups (0.14.0), in the phone's format (photos/<receipt id>/<page>): a backup with
 * photos restores them on another computer under that computer's own photo key; a backup without any (older ones, or
 * the program before 0.14.0) still restores, with none; a damaged photo entry changes nothing. Invented data, test keys.
 */
class DesktopBackupPhotosTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDate.of(2030, 5, 1)
    private val photo1 = ByteArray(120_000) { (it % 251).toByte() }
    private val photo2 = "sample page two".toByteArray()

    private inner class Desk(name: String) {
        val dir: File = tmp.newFolder(name)
        val opened = runBlocking { assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(dir, clock = { 1_000L })) }
        val store: BudgetStore = opened.store
        val key = ByteArray(32) { (it * 3 + name.hashCode()).toByte() }
        val images = ReceiptImages(File(dir, "receipts"), { key })
        val backups = DesktopBackups(store.db, opened.file, "desktop-test", { 1_000 }, images, File(dir, "receipts-restore"))
        /** As the program wrote them before 0.14.0: no photos. */
        val older = DesktopBackups(store.db, opened.file, "desktop-0.13.0", { 1_000 })
        init { runBlocking { store.ensureSystemEnvelopes(); store.ensureCategories() } }
    }

    private val recovery = RecoveryKey.generate()

    private suspend fun Desk.withReceipt(): String {
        val a = BudgetActions(store)
        a.saveAccount(null, "Sample Checking", AccountType.BANK, 100_00, day)
        val rid = net.darkrelay.deaddropfinance.data.newId()
        store.dao.upsertReceipt(net.darkrelay.deaddropfinance.data.ReceiptEntity(id = rid, store = "SAMPLE MARKET", date = day, total = 12_49, pages = 2, createdAt = 1, updatedAt = 1))
        images.write(rid, 0, photo1)
        images.write(rid, 1, photo2)
        return rid
    }

    private suspend fun DesktopBackups.bytes(): ByteArray = ByteArrayOutputStream().also { write(it, recovery) }.toByteArray()

    @Test fun aBackupCarriesThePhotosAndAnotherComputerGetsThemUnderItsOwnKey() = runBlocking<Unit> {
        val a = Desk("A")
        val rid = a.withReceipt()
        val bytes = a.backups.bytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("sample page two"), "photos are inside the encryption")
        val b = Desk("B")
        val s = b.backups.restore(ByteArrayInputStream(bytes), BackupSecret.Key(recovery))
        assertEquals(1, s.receipts)
        assertEquals(2, s.photos)
        assertContentEquals(photo1, b.images.read(rid, 0))
        assertContentEquals(photo2, b.images.read(rid, 1))
        // on disk under B's key, not A's
        assertEquals(null, ReceiptImages(File(b.dir, "receipts"), { a.key }).read(rid, 0))
        assertFalse(File(b.dir, "receipts-restore").exists(), "the staging folder is gone")
    }

    @Test fun anOlderBackupWithoutPhotosRestoresAndLeavesNone() = runBlocking<Unit> {
        val a = Desk("A")
        a.withReceipt()
        val old = a.older.bytes()
        val b = Desk("B")
        val here = b.withReceipt()
        val s = b.backups.restore(ByteArrayInputStream(old), BackupSecret.Key(recovery))
        assertEquals(0, s.photos)
        assertEquals(1, s.receipts)
        // the budget is the backup's: this computer's old photos go with its old budget, as on the phone
        assertTrue(b.images.stored().isEmpty())
        assertEquals(null, b.images.read(here, 0))
    }

    @Test fun aNewBackupStillRestoresWhereNoPhotosAreKept() = runBlocking<Unit> {
        val a = Desk("A")
        a.withReceipt()
        val b = Desk("B")
        // a restore that carries no photo store (the program's tests before 0.14.0) reads past them
        assertEquals(1, b.older.restore(ByteArrayInputStream(a.backups.bytes()), BackupSecret.Key(recovery)).receipts)
    }

    @Test fun aDamagedPhotoEntryChangesNothing() = runBlocking<Unit> {
        val b = Desk("B")
        val here = b.withReceipt()
        val good = ByteArrayOutputStream()
        ZipOutputStream(BackupCrypto.encryptingWithKey(good, recovery)).use { zip ->
            fun entry(n: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(n)); zip.write(bytes); zip.closeEntry() }
            entry("manifest.json", JSONObject().put("format", DesktopBackups.FORMAT_NAME).put("version", 1).put("schema", 1).toString().toByteArray())
            for (t in listOf("account", "envelope", "txn")) entry("tables/$t.json", JSONArray().toString().toByteArray())
            entry("photos/../escape/0", photo2)
        }
        assertFailsWith<BackupException> { b.backups.restore(ByteArrayInputStream(good.toByteArray()), BackupSecret.Key(recovery)) }
        assertContentEquals(photo1, b.images.read(here, 0))
        assertFalse(File(b.dir, "receipts-restore").exists())
    }
}
