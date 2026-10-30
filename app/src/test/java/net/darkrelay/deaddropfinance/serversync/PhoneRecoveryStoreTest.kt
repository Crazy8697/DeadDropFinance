package net.darkrelay.deaddropfinance.serversync

import java.io.File
import java.nio.file.Files
import net.darkrelay.deaddropfinance.backup.RecoveryKey
import net.darkrelay.deaddropfinance.backup.RecoveryKeys
import net.darkrelay.deaddropfinance.data.KeyWrapper
import net.darkrelay.deaddropfinance.data.SecretStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's recovery keys (1.6.0) live in SecretStore, wrapped (here by a stand-in for the Keystore wrapper): never
 * in plain text in its file. Made once, shown until I saved it, an older key kept when the account's arrives.
 */
class PhoneRecoveryStoreTest {

    /** The Keystore wrapper's stand-in: what's stored is never the plain text. */
    private object TestWrapper : KeyWrapper {
        override fun wrap(plain: ByteArray) = "WRAP".toByteArray() + plain.map { (it.toInt() xor 0x5a).toByte() }
        override fun unwrap(blob: ByteArray) = blob.copyOfRange(4, blob.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    private val dir: File = Files.createTempDirectory("ddf-recovery").toFile()
    private val file = File(dir, SecretStore.FILE_NAME)
    private val secrets = SecretStore(file, TestWrapper)

    @Test fun theKeyIsKeptWrappedAndReadsBack() {
        val keys = RecoveryKeys(PhoneRecoveryStore { secrets })
        val k = keys.ensure()
        assertArrayEquals(k, keys.toShow())
        val hex = k.joinToString("") { "%02x".format(it) }
        assertFalse("never plain in the file", String(file.readBytes(), Charsets.ISO_8859_1).contains(hex))
        keys.confirmSaved()
        assertNull(keys.toShow())
        // the app reopened
        val again = RecoveryKeys(PhoneRecoveryStore { SecretStore(file, TestWrapper) })
        assertArrayEquals(k, again.current())
        assertNull(again.toShow())
        val account = RecoveryKey.generate()
        again.adopt(account)
        assertArrayEquals(account, again.current())
        assertEquals(1, again.old().size)
        assertArrayEquals(k, again.old().single())
        assertTrue(secrets.names().all { it.startsWith("recovery_key") })
    }
}
