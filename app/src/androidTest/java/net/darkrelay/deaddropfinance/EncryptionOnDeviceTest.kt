package net.darkrelay.deaddropfinance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.DatabaseOpener
import net.darkrelay.deaddropfinance.data.DbKeys
import net.darkrelay.deaddropfinance.data.KeystoreWrapper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks of the database encryption, run by hand on a phone in the debug build
 * (net.darkrelay.deaddropfinance.debug), which has its own data, never the release app's. Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
class EncryptionOnDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun dbFiles() = listOf("", "-wal", "-journal", "-shm")
        .map { File(ctx.getDatabasePath(AppDatabase.NAME).path + it) }.filter { it.exists() }

    /** Writes a row carrying a unique marker, closes the database, and returns the marker. */
    private fun writeMarker(): String {
        val marker = "DDFMARKER" + UUID.randomUUID().toString().replace("-", "")
        val opened = DatabaseOpener.open(ctx)
        if (opened !is DatabaseOpener.Opened.Ok) fail("database didn't open: $opened")
        val store = (opened as DatabaseOpener.Opened.Ok).store
        runBlocking {
            store.write { put(null, AccountEntity(name = marker, type = AccountType.CASH, startDate = LocalDate.of(2030, 1, 1), createdAt = 0, updatedAt = 0)) }
        }
        store.db.close()
        return marker
    }

    @Test fun rawFilesHaveNoPlainHeaderAndNoMarker() {
        val marker = writeMarker()
        val files = dbFiles()
        assertTrue("the database file exists", files.any { it.name == AppDatabase.NAME })
        for (f in files) {
            val bytes = f.readBytes()
            val head = String(bytes, 0, minOf(15, bytes.size), Charsets.ISO_8859_1)
            assertFalse("no plain SQLite header in ${f.name}", head == "SQLite format 3")
            assertFalse("the marker is not readable in ${f.name}", String(bytes, Charsets.ISO_8859_1).contains(marker))
        }
    }

    @Test fun aWrongKeyCannotOpenItAndTheRightKeyCan() {
        writeMarker()
        val path = ctx.getDatabasePath(AppDatabase.NAME).path
        System.loadLibrary("sqlcipher")
        // all-zero key, built in code
        val wrong = "x'" + "0".repeat(64) + "'"
        var opened = false
        try {
            SQLiteDatabase.openDatabase(path, wrong, null, SQLiteDatabase.OPEN_READONLY, null, null).use {
                it.rawQuery("select count(*) from sqlite_master", emptyArray()).use { c -> c.moveToFirst() }
                opened = true
            }
        } catch (expected: Exception) {
            // wrong key: the database must not open
        }
        assertFalse("a wrong key opened the database", opened)

        val keys = DbKeys(File(ctx.filesDir, "keys"), KeystoreWrapper()).loadOrCreate(databaseExists = true)
        assertTrue("the stored key unwraps", keys is DbKeys.Result.Ok)
        val key = (keys as DbKeys.Result.Ok).key
        val pw = String(DbKeys.rawKeyPassword(key), Charsets.US_ASCII)
        key.fill(0)
        SQLiteDatabase.openDatabase(path, pw, null, SQLiteDatabase.OPEN_READONLY, null, null).use {
            it.rawQuery("select count(*) from sqlite_master", emptyArray()).use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("the right key reads the tables", c.getInt(0) > 0)
            }
        }
    }

    @Test fun theKeystoreWrapRoundTrips() {
        val alias = "ddf_test_roundtrip_" + UUID.randomUUID().toString().take(8)
        val w = KeystoreWrapper(alias)
        val plain = ByteArray(32) { (it * 7).toByte() }
        val blob = w.wrap(plain)
        assertFalse("the wrapped blob is not the plain key", blob.toList().windowed(plain.size).any { it == plain.toList() })
        assertArrayEquals(plain, w.unwrap(blob))
        assertEquals(1.toByte(), blob[0])
        // a flipped byte must fail (GCM tag), not return garbage
        val bad = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        var failed = false
        try { w.unwrap(bad) } catch (e: Exception) { failed = true }
        assertTrue("a tampered blob fails to unwrap", failed)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
