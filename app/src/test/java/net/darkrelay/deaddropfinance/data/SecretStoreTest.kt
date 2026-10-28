package net.darkrelay.deaddropfinance.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The secrets store with a fake wrapper (the Keystore only exists on a phone). Values are made up here. */
class SecretStoreTest {

    private class Fake(var broken: Boolean = false) : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x6B).toByte() }
        override fun unwrap(blob: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("gone")
            return blob.drop(1).map { (it.toInt() xor 0x6B).toByte() }.toByteArray()
        }
    }

    private fun file() = File(Files.createTempDirectory("ddf-sec").toFile(), SecretStore.FILE_NAME)

    @Test fun secretsRoundTripAndTheFileIsNotPlainText() {
        val f = file()
        val w = Fake()
        val value = "token-" + System.nanoTime()
        SecretStore(f, w).put("receipts.key", value)
        assertEquals(value, SecretStore(f, w).get("receipts.key"))
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains(value))
    }

    @Test fun removingSomeSecretsLeavesTheRest() {
        val s = SecretStore(file(), Fake())
        s.put("old.one", "a")
        s.put("old.two", "b")
        s.put("app.setting", "c")
        s.removeWhere { it == "old.one" }
        assertNull(s.get("old.one"))
        assertEquals(setOf("old.two", "app.setting"), s.names())
    }

    @Test fun anUnreadableStoreIsNeverOverwritten() {
        val f = file()
        val w = Fake()
        SecretStore(f, w).put("x", "y")
        val bytes = f.readBytes()
        w.broken = true
        try {
            SecretStore(f, w).put("x", "z")
            fail("must not write over secrets it can't read")
        } catch (e: SecretStore.Unavailable) {
            assertTrue(e.message!!.contains("can't be unlocked"))
        }
        assertArrayEquals(bytes, f.readBytes())
    }
}
