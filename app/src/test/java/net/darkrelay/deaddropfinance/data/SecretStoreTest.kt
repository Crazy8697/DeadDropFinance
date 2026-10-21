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
        SecretStore(f, w).put(SecretStore.tellerToken("c1"), value)
        assertEquals(value, SecretStore(f, w).get(SecretStore.tellerToken("c1")))
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains(value))
        assertEquals("..." + value.takeLast(4), SecretStore(f, w).hint(SecretStore.tellerToken("c1")))
    }

    @Test fun removingAConnectionsSecretsLeavesTheRest() {
        val s = SecretStore(file(), Fake())
        s.put(SecretStore.tellerToken("c1"), "a")
        s.put(SecretStore.simplefinUrl("c2"), "b")
        s.put(SecretStore.TELLER_CERT, "c")
        s.removeWhere(SecretStore.forConnection("c1"))
        assertNull(s.get(SecretStore.tellerToken("c1")))
        assertEquals(setOf(SecretStore.simplefinUrl("c2"), SecretStore.TELLER_CERT), s.names())
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
