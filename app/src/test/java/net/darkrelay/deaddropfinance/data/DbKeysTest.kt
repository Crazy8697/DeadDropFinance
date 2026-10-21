package net.darkrelay.deaddropfinance.data

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The database key's life cycle, with a fake wrapper (Android Keystore only exists on a phone). */
class DbKeysTest {

    /** XORs with a fixed byte: enough to prove the stored file isn't the plain key. */
    private class FakeWrapper(var broken: Boolean = false) : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun unwrap(blob: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("the Keystore key is gone")
            return blob.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("ddf-keys").toFile().also { it.deleteOnExit() }

    @Test fun firstLaunchMakesAKeyAndTheSecondReadsItBack() {
        val dir = tempDir()
        val w = FakeWrapper()
        val first = DbKeys(dir, w, SecureRandom()).loadOrCreate(databaseExists = false) as DbKeys.Result.Ok
        assertEquals(32, first.key.size)
        val stored = File(dir, DbKeys.WRAPPED_FILE).readBytes()
        assertTrue("the stored file is not the plain key", !stored.drop(1).toByteArray().contentEquals(first.key))
        val second = DbKeys(dir, w).loadOrCreate(databaseExists = true) as DbKeys.Result.Ok
        assertArrayEquals(first.key, second.key)
    }

    @Test fun aLostKeystoreKeyNeverTouchesTheData() {
        val dir = tempDir()
        val w = FakeWrapper()
        DbKeys(dir, w).loadOrCreate(databaseExists = false)
        val keyFile = File(dir, DbKeys.WRAPPED_FILE)
        val keyBytes = keyFile.readBytes()
        val db = File(dir, "budget.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        w.broken = true
        val r = DbKeys(dir, w).loadOrCreate(databaseExists = true)
        assertTrue(r is DbKeys.Result.Unavailable)
        // nothing deleted or overwritten
        assertArrayEquals(keyBytes, keyFile.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), db.readBytes())
    }

    @Test fun aMissingKeyWithAnExistingDatabaseIsNeverReplaced() {
        val dir = tempDir()
        val r = DbKeys(dir, FakeWrapper()).loadOrCreate(databaseExists = true)
        assertTrue(r is DbKeys.Result.Unavailable)
        assertTrue(!File(dir, DbKeys.WRAPPED_FILE).exists())
    }

    @Test fun rawKeyFormIsSqlcipherHex() {
        val key = ByteArray(32) { it.toByte() }
        val pw = String(DbKeys.rawKeyPassword(key), Charsets.US_ASCII)
        // built here rather than written out, so no 64-digit hex literal sits in the sources
        val expected = "x'" + (0 until 32).joinToString("") { "%02x".format(it) } + "'"
        assertEquals(expected, pw)
    }
}
