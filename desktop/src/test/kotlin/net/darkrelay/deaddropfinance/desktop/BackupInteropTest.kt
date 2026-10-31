package net.darkrelay.deaddropfinance.desktop

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.backup.BackupSecret
import net.darkrelay.deaddropfinance.backup.DesktopBackups
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.domain.BudgetActions
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The program's half of the phone and program backup run (0.14.0, see the phone's BackupInteropTest): the phone's
 * backups (with photos, and without) restore on the program, through the model's own restore; then the program writes
 * its own (with photos, and as 0.13.0 did, without) for the phone to restore. Skipped in ordinary test runs.
 */
class BackupInteropTest {

    @get:Rule val tmp = TemporaryFolder()
    private val dir = System.getenv("DDF_INTEROP_DIR")?.let { File(it) }
    private val key = ByteArray(32) { (it * 11 + 3).toByte() }

    @Test fun thePhonesBackupsRestoreHereAndThisProgramWritesItsOwn() = runBlocking<Unit> {
        assumeTrue("the interop run only", dir != null && System.getenv("DDF_INTEROP_STAGE") == "program")
        val recovery = File(dir, "recovery.key").readBytes()
        val data = tmp.newFolder("program")
        val engine = Engine.start(data)
        val opened = assertIs<DesktopDatabase.Opened.Ok>(engine.opened)
        val model = AppModel(engine)
        model.refresh()
        val images = ReceiptImages(File(data, "receipts"), { key })
        model.receiptImages = images
        // the phone's, with photos: through the program's own Restore
        assertTrue(model.restore(File(dir, "phone-photos.ddfbak"), BackupSecret.Key(recovery)), model.message.toString())
        assertTrue(model.message!!.first.contains("2 receipt photos"), model.message.toString())
        assertContentEquals(PHOTO_1, images.read(RECEIPT, 0))
        assertContentEquals(PHOTO_2, images.read(RECEIPT, 1))
        assertTrue(model.budget!!.accounts.any { it.name == "Sample Phone Checking" })
        assertEquals(1, opened.store.dao.receiptItemsNow().count { it.receiptId == RECEIPT })
        // the phone's without photos: restores, with none
        assertTrue(model.restore(File(dir, "phone-none.ddfbak"), BackupSecret.Key(recovery)), model.message.toString())
        assertTrue(images.stored().isEmpty())
        assertTrue(model.budget!!.accounts.any { it.name == "Sample Phone Checking" })

        // now the program's own: a budget with the receipt and its two photos
        model.restore(File(dir, "phone-photos.ddfbak"), BackupSecret.Key(recovery))
        val a = BudgetActions(opened.store)
        a.saveAccount(null, "Sample Program Checking", AccountType.BANK, 50_00, DAY)
        val withPhotos = DesktopBackups(opened.store.db, opened.file, "desktop-interop", photos = images, stagingDir = File(data, "receipts-restore"))
        assertEquals(2, File(dir, "program-photos.ddfbak").outputStream().use { withPhotos.write(it, recovery) }.photos)
        val older = DesktopBackups(opened.store.db, opened.file, "desktop-0.13.0")
        assertEquals(0, File(dir, "program-none.ddfbak").outputStream().use { older.write(it, recovery) }.photos)
        engine.close()
    }

    companion object {
        /** The phone half's invented data (app/.../backup/BackupInteropTest.kt). */
        val DAY: LocalDate = LocalDate.of(2030, 6, 2)
        const val RECEIPT = "interop-receipt-1"
        val PHOTO_1 = ByteArray(120_000) { (it % 251).toByte() }
        val PHOTO_2 = "sample page two".toByteArray()
    }
}
