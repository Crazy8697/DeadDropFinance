package net.darkrelay.deaddropfinance.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that lives in Android Keystore (it never leaves the secure hardware and can't be
 * exported). Blob: version byte 1, then the 12-byte IV, then the ciphertext with its tag.
 */
class KeystoreWrapper(private val alias: String = ALIAS) : KeyWrapper {

    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IllegalStateException("the Keystore key is gone")
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key(create = true))
        val iv = c.iv
        val ct = c.doFinal(plain)
        return byteArrayOf(1) + iv + ct
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > 13 && blob[0] == 1.toByte()) { "not a wrapped key" }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, blob, 1, 12))
        return c.doFinal(blob, 13, blob.size - 13)
    }

    companion object {
        const val ALIAS = "ddf_database_key_wrap"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/**
 * The database key: 32 random bytes made on first launch, kept only wrapped (in the app's private files). The
 * plain key exists only in memory. If the wrapped key can't be read or unwrapped, nothing is deleted or
 * overwritten: the caller shows that the data can't be opened on this phone.
 */
class DbKeys(
    private val dir: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    sealed class Result {
        class Ok(val key: ByteArray) : Result()
        data class Unavailable(val reason: String) : Result()
    }

    val wrappedFile = File(dir, WRAPPED_FILE)

    /** [databaseExists]: a database file is already there (so a missing key must never mean "start over"). */
    fun loadOrCreate(databaseExists: Boolean): Result {
        if (wrappedFile.exists()) {
            return try {
                val key = wrapper.unwrap(wrappedFile.readBytes())
                if (key.size != KEY_BYTES) Result.Unavailable("the stored key has the wrong length") else Result.Ok(key)
            } catch (t: Throwable) {
                Result.Unavailable("the stored key can't be unlocked on this phone")
            }
        }
        if (databaseExists) return Result.Unavailable("the database's key is missing")
        return try {
            val key = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            val blob = wrapper.wrap(key)
            dir.mkdirs()
            val tmp = File(dir, "$WRAPPED_FILE.tmp")
            tmp.writeBytes(blob)
            if (!tmp.renameTo(wrappedFile)) {
                tmp.delete()
                return Result.Unavailable("the key couldn't be saved")
            }
            Result.Ok(key)
        } catch (t: Throwable) {
            Result.Unavailable("a key couldn't be made on this phone")
        }
    }

    companion object {
        const val WRAPPED_FILE = "database.key.wrapped"
        const val KEY_BYTES = 32

        /**
         * SQLCipher's raw-key form x'<64 hex>', as bytes, so no key derivation runs on every open. The returned
         * array is what SQLCipher keeps; the caller wipes the plain [key] after this.
         */
        fun rawKeyPassword(key: ByteArray): ByteArray {
            val hex = "0123456789abcdef".toByteArray()
            val out = ByteArray(3 + key.size * 2)
            out[0] = 'x'.code.toByte(); out[1] = '\''.code.toByte()
            key.forEachIndexed { i, b ->
                out[2 + i * 2] = hex[(b.toInt() shr 4) and 0xF]
                out[3 + i * 2] = hex[b.toInt() and 0xF]
            }
            out[out.size - 1] = '\''.code.toByte()
            return out
        }
    }
}
