package net.darkrelay.deaddropfinance.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.domain.BudgetActions
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backups between the phone and the program (1.8.1 and 0.14.0), with receipt photos and without, in both directions.
 * The phone's backup code is Android's and the program's is the desktop's, so the two halves run in their own test
 * runs, handing files over in DDF_INTEROP_DIR (a temporary folder, invented data, a test recovery key made for the run):
 * this test writes the phone's backups (DDF_INTEROP_STAGE=phone-write), the desktop's BackupInteropTest restores them on
 * the program and writes the program's, and this test restores those (phone-read). Skipped in ordinary test runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupInteropTest {

    private val dir = System.getenv("DDF_INTEROP_DIR")?.let { File(it) }
    private val stage = System.getenv("DDF_INTEROP_STAGE")
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val store = BudgetStore(db)
    private val key = ByteArray(32) { (it * 5 + 1).toByte() }
    private val work = java.nio.file.Files.createTempDirectory("ddf-interop-phone").toFile()
    private val images = ReceiptImages(File(work, "receipts"), { key })

    @After fun tearDown() { db.close(); work.deleteRecursively() }

    private fun recovery() = File(dir, "recovery.key").readBytes()

    @Test fun phoneWritesItsBackupsWithPhotosAndWithout() = runBlocking<Unit> {
        assumeTrue("the interop run only", dir != null && stage == "phone-write")
        store.ensureSystemEnvelopes(); store.ensureCategories()
        val a = BudgetActions(store)
        val acct = a.saveAccount(null, INTEROP_ACCOUNT_PHONE, AccountType.BANK, 300_00, DAY)
        a.saveTransaction(null, BudgetActions.TransactionDraft(acct, DAY, "SAMPLE MARKET", "", -12_49, listOf(BudgetActions.LineDraft(null, -12_49))))
        store.write {
            put(null, ReceiptEntity(id = RECEIPT, store = "SAMPLE MARKET", date = DAY, total = 12_49, pages = 2, createdAt = now, updatedAt = now))
            put(null, ReceiptItemEntity(receiptId = RECEIPT, text = "SAMPLE BREAD", name = "sample bread", amount = 12_49, createdAt = now, updatedAt = now))
        }
        images.write(RECEIPT, 0, PHOTO_1)
        images.write(RECEIPT, 1, PHOTO_2)
        dir!!.mkdirs()
        File(dir, "recovery.key").writeBytes(RecoveryKey.generate())
        val withPhotos = Backups(db, images, File(work, "staging"), null, "1.8.1-interop")
        val s = File(dir, "phone-photos.ddfbak").outputStream().use { withPhotos.write(it, recovery()) }
        assertEquals(2, s.photos)
        // a backup with no photos: what an older backup, or one with no receipts, holds
        val none = Backups(db, null, File(work, "staging"), null, "1.8.1-interop")
        assertEquals(0, File(dir, "phone-none.ddfbak").outputStream().use { none.write(it, recovery()) }.photos)
    }

    @Test fun phoneRestoresTheProgramsBackupsWithPhotosAndWithout() = runBlocking<Unit> {
        assumeTrue("the interop run only", dir != null && stage == "phone-read")
        store.ensureSystemEnvelopes()
        val backups = Backups(db, images, File(work, "staging"), null, "1.8.1-interop")
        // a photo already here: a restore replaces the photos with the backup's
        images.write("sample-old", 0, "old".toByteArray())
        val s = File(dir, "program-photos.ddfbak").inputStream().use { backups.restore(it, BackupSecret.Key(recovery())) }
        assertEquals(1, s.receipts)
        assertEquals(2, s.photos)
        assertArrayEquals(PHOTO_1, images.read(RECEIPT, 0))
        assertArrayEquals(PHOTO_2, images.read(RECEIPT, 1))
        assertEquals(listOf(RECEIPT to 0, RECEIPT to 1), images.stored())
        val b = BudgetActions(store).snapshot()
        assertTrue(b.accounts.any { it.name == INTEROP_ACCOUNT_PROGRAM })
        assertEquals(1, store.dao.receiptItemsNow().count { it.receiptId == RECEIPT && !it.deleted })
        // the program's backup without photos (as 0.13.0 wrote them): restores, with none
        val n = File(dir, "program-none.ddfbak").inputStream().use { backups.restore(it, BackupSecret.Key(recovery())) }
        assertEquals(0, n.photos)
        assertEquals(1, n.receipts)
        assertTrue(images.stored().isEmpty())
    }

    companion object {
        /** The same invented data in both halves (the desktop's BackupInteropTest has its own copy). */
        val DAY: LocalDate = LocalDate.of(2030, 6, 2)
        const val RECEIPT = "interop-receipt-1"
        const val INTEROP_ACCOUNT_PHONE = "Sample Phone Checking"
        const val INTEROP_ACCOUNT_PROGRAM = "Sample Program Checking"
        val PHOTO_1 = ByteArray(120_000) { (it % 251).toByte() }
        val PHOTO_2 = "sample page two".toByteArray()
    }
}
