package net.darkrelay.deaddropfinance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.DatabaseOpener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one on-device check: the budget database file on a real phone is encrypted. Runs in the debug build
 * (net.darkrelay.deaddropfinance.debug), which has its own data, never the release app's.
 */
@RunWith(AndroidJUnit4::class)
class EncryptionOnDeviceTest {

    @Test fun theDatabaseFileIsNotReadableAsPlainSqlite() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = "EncryptionCheckMarker"
        val opened = DatabaseOpener.open(ctx)
        if (opened !is DatabaseOpener.Opened.Ok) fail("database didn't open: $opened")
        val store = (opened as DatabaseOpener.Opened.Ok).store
        runBlocking { store.write { put(null, AccountEntity(name = marker, type = AccountType.CASH, startDate = LocalDate.of(2030, 1, 1), createdAt = 0, updatedAt = 0)) } }
        store.db.close()

        val files = listOf("", "-wal", "-journal").map { File(ctx.getDatabasePath(AppDatabase.NAME).path + it) }.filter { it.exists() }
        assertTrue("database file exists", files.isNotEmpty())
        val main = files.first().readBytes()
        assertFalse("no plain SQLite header", String(main, 0, 15, Charsets.US_ASCII) == "SQLite format 3")
        for (f in files) assertFalse("marker text isn't readable in ${f.name}", String(f.readBytes(), Charsets.ISO_8859_1).contains(marker))
    }
}
