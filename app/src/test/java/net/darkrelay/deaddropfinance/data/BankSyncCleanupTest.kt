package net.darkrelay.deaddropfinance.data

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import net.darkrelay.deaddropfinance.sync.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bank sync removed (1.3.0), outside the database: the saved SimpleFIN (and old Teller) logins go, the receipt photo
 * key stays, bank sync's settings go and every other setting stays, and the background sync job is cancelled.
 * Invented values only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BankSyncCleanupTest {

    private class FakeWrapper : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain
        override fun unwrap(blob: ByteArray) = blob.drop(1).toByteArray()
    }

    @Test fun theSavedLoginAndBankSyncSettingsGoAndEverythingElseStays() {
        val dir = Files.createTempDirectory("ddf-cleanup").toFile()
        try {
            val secrets = SecretStore(File(dir, SecretStore.FILE_NAME), FakeWrapper())
            secrets.put("simplefin.url.c1", "https://invented.example/simplefin")
            secrets.put("teller.token.c0", "invented")
            secrets.put("receipts.key", "invented key")
            val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("cleanup-test", Context.MODE_PRIVATE)
            prefs.edit(commit = true) {
                clear(); putBoolean("background", true); putInt("interval_hours", 12); putLong("attempt.c1", 5)
                putBoolean("auto_approve", true); putBoolean("setup_done", true)
            }
            val cancelled = mutableListOf<String>()
            val gone = BankSyncCleanup(secrets, SyncSettings(prefs)) { cancelled += it }.run()
            assertEquals(2, gone)
            assertEquals(setOf("receipts.key"), secrets.names())
            assertEquals("invented key", secrets.get("receipts.key"))
            assertFalse(prefs.contains("background"))
            assertFalse(prefs.contains("interval_hours"))
            assertFalse(prefs.contains("attempt.c1"))
            assertTrue(prefs.getBoolean("auto_approve", false))
            assertTrue(prefs.getBoolean("setup_done", false))
            assertEquals(listOf(BankSyncCleanup.SYNC_WORK), cancelled)
            // a second start finds nothing left to remove
            assertEquals(0, BankSyncCleanup(secrets, SyncSettings(prefs)).run())
        } finally {
            dir.deleteRecursively()
        }
    }
}
