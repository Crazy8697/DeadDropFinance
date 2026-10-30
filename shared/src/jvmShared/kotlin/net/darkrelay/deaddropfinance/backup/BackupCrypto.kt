package net.darkrelay.deaddropfinance.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A backup that can't be read, with a plain reason for the screen. Nothing was changed when it's thrown. */
class BackupException(message: String) : Exception(message)

/** What opens a backup: the recovery key (format 2, from 1.6.0) or, for one made before 1.6.0, its password (format 1). */
sealed class BackupSecret {
    class OldPassword(val chars: CharArray) : BackupSecret()
    class Key(val key: ByteArray) : BackupSecret()
}

/**
 * The backup file's encryption. A key is made from the backup password (PBKDF2 with HMAC-SHA256, a random salt and
 * [ITERATIONS] rounds); the contents are cut into chunks, each sealed with AES-256-GCM. Every chunk's nonce carries
 * its number and the header is bound into every chunk, so chunks can't be reordered, swapped between files or cut
 * off: the last chunk is marked as last, and a file that ends before it is refused.
 *
 * File: "DDFBAK", format 1, rounds (4 bytes), salt (16), nonce prefix (8), then chunks of [last flag (1), length (4),
 * sealed bytes]. The password is never stored.
 *
 * Format 2 (1.6.0), every new backup: the key comes from the recovery key instead of a password (HMAC-SHA256 of a
 * label and a random salt, keyed with the recovery key; it is already 256 random bits, so no slow key stretching).
 * File: "DDFBAK", format 2, key id (16 bytes, RecoveryKey.id, so a different key is named as such), salt (16), nonce
 * prefix (8), then the same chunks. Format 1 files still open with their password.
 */
object BackupCrypto {
    private val MAGIC = "DDFBAK".toByteArray()
    private const val FORMAT: Byte = 1
    private const val FORMAT_KEY: Byte = 2
    private const val KEY_ID = 16
    private const val SALT = 16
    private const val PREFIX = 8
    private const val TAG_BITS = 128
    private const val CHUNK = 64 * 1024
    private const val HEADER = 6 + 1 + 4 + SALT + PREFIX
    private const val HEADER_KEY = 6 + 1 + KEY_ID + SALT + PREFIX

    /** PBKDF2 rounds for a new backup. */
    const val ITERATIONS = 600_000
    /** Rounds a file may ask for: below is too weak to trust, above would hang the phone. */
    private val ROUNDS_ALLOWED = 1_000..5_000_000

    private fun key(passphrase: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, rounds, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, index: Int) = ByteBuffer.allocate(12).put(prefix).putInt(index).array()

    private fun aad(header: ByteArray, last: Boolean) = header + byteArrayOf(if (last) 1 else 0)

    private fun keyFrom(recoveryKey: ByteArray, salt: ByteArray): SecretKeySpec {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(recoveryKey, "HmacSHA256"))
        return SecretKeySpec(mac.doFinal("deaddrop backup v2".toByteArray() + salt), "AES")
    }

    private fun idBytes(recoveryKey: ByteArray) = RecoveryKey.id(recoveryKey).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Which secret a backup file opens with, from its first 7 bytes; null when it isn't a backup (or is a newer format). */
    fun kindOf(head: ByteArray): Class<out BackupSecret>? {
        if (head.size < MAGIC.size + 1 || !head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        return when (head[MAGIC.size]) {
            FORMAT -> BackupSecret.OldPassword::class.java
            FORMAT_KEY -> BackupSecret.Key::class.java
            else -> null
        }
    }

    /** How many first bytes [keyIdOf] needs. */
    const val HEAD_BYTES = 6 + 1 + 16

    /** The recovery key id a format 2 backup was made with (from its first [HEAD_BYTES] bytes), or null for any other file. */
    fun keyIdOf(head: ByteArray): String? {
        if (kindOf(head) != BackupSecret.Key::class.java || head.size < HEAD_BYTES) return null
        return head.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + KEY_ID).joinToString("") { "%02x".format(it) }
    }

    /**
     * Wraps [out]: what's written is encrypted under [passphrase] (format 1). Since 1.6.0 new backups use the recovery
     * key ([encryptingWithKey]); this stays for the tests of older backups.
     */
    fun encrypting(out: OutputStream, passphrase: CharArray, rounds: Int = ITERATIONS): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(SALT).also { random.nextBytes(it) }
        val prefix = ByteArray(PREFIX).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(FORMAT).putInt(rounds).put(salt).put(prefix).array()
        return sealing(out, header, key(passphrase, salt, rounds), prefix)
    }

    /** Wraps [out]: what's written is encrypted under the recovery key (format 2). Closing it writes the last chunk (and closes [out]). */
    fun encryptingWithKey(out: OutputStream, recoveryKey: ByteArray): OutputStream {
        require(recoveryKey.size == RecoveryKey.BYTES)
        val random = SecureRandom()
        val salt = ByteArray(SALT).also { random.nextBytes(it) }
        val prefix = ByteArray(PREFIX).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_KEY).put(MAGIC).put(FORMAT_KEY).put(idBytes(recoveryKey)).put(salt).put(prefix).array()
        return sealing(out, header, keyFrom(recoveryKey, salt), prefix)
    }

    private fun sealing(out: OutputStream, header: ByteArray, k: SecretKeySpec, prefix: ByteArray): OutputStream {
        out.write(header)
        return object : OutputStream() {
            private val buf = ByteArrayOutputStream(CHUNK)
            private var index = 0
            private var closed = false

            private fun seal(bytes: ByteArray, last: Boolean) {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce(prefix, index++)))
                c.updateAAD(aad(header, last))
                val sealed = c.doFinal(bytes)
                out.write(if (last) 1 else 0)
                out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
                out.write(sealed)
            }

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off
                var left = len
                while (left > 0) {
                    // a full chunk is only sealed once more is coming, so the last one is always marked last
                    if (buf.size() == CHUNK) { seal(buf.toByteArray(), last = false); buf.reset() }
                    val n = minOf(left, CHUNK - buf.size())
                    buf.write(b, o, n)
                    o += n; left -= n
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                seal(buf.toByteArray(), last = true)
                out.close()
            }
        }
    }

    /** A format 1 backup, opened with its password (as before 1.6.0). */
    fun decrypting(input: InputStream, passphrase: CharArray): InputStream = decrypting(input, BackupSecret.OldPassword(passphrase))

    /**
     * Wraps [input] to read a backup's contents: format 2 with the recovery key, format 1 with its password. A file that
     * isn't a backup, the wrong key or password, a changed byte or a file cut short throws BackupException as it's read.
     */
    fun decrypting(input: InputStream, opener: BackupSecret): InputStream {
        val din = DataInputStream(input)
        val start = ByteArray(MAGIC.size + 1)
        try { din.readFully(start) } catch (e: EOFException) { throw BackupException("That isn't a Dead Drop Finance backup file.") }
        if (!start.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw BackupException("That isn't a Dead Drop Finance backup file.")
        val format = start[MAGIC.size]
        if (format != FORMAT && format != FORMAT_KEY) throw BackupException("This backup was made by a newer version of the app. Update the app, then restore.")
        val rest = ByteArray((if (format == FORMAT) HEADER else HEADER_KEY) - start.size)
        try { din.readFully(rest) } catch (e: EOFException) { throw BackupException("That isn't a Dead Drop Finance backup file.") }
        val header = start + rest
        val bb = ByteBuffer.wrap(header, MAGIC.size + 1, header.size - MAGIC.size - 1)
        val k: SecretKeySpec
        val prefix: ByteArray
        val wrong: String
        if (format == FORMAT) {
            val pass = (opener as? BackupSecret.OldPassword)?.chars
                ?: throw BackupException("This backup was made before 1.6.0: it opens with the password it was made with, not a recovery key.")
            val rounds = bb.int
            if (rounds !in ROUNDS_ALLOWED) throw BackupException("The backup file is damaged.")
            val salt = ByteArray(SALT).also { bb.get(it) }
            prefix = ByteArray(PREFIX).also { bb.get(it) }
            k = key(pass, salt, rounds)
            wrong = "Wrong password, or the backup file is damaged."
        } else {
            val rk = (opener as? BackupSecret.Key)?.key
                ?: throw BackupException("This backup opens with a recovery key, not a password.")
            val id = ByteArray(KEY_ID).also { bb.get(it) }
            val salt = ByteArray(SALT).also { bb.get(it) }
            prefix = ByteArray(PREFIX).also { bb.get(it) }
            if (rk.size != RecoveryKey.BYTES || !id.contentEquals(idBytes(rk))) {
                throw BackupException("That recovery key doesn't open this backup: it was made with a different key.")
            }
            k = keyFrom(rk, salt)
            wrong = "The backup file is damaged."
        }
        return object : InputStream() {
            private var cur = ByteArray(0)
            private var pos = 0
            private var index = 0
            private var done = false

            private fun next(): Boolean {
                if (done) {
                    if (din.read() != -1) throw BackupException("The backup file is damaged.")
                    return false
                }
                val flag: Int
                val len: Int
                try {
                    flag = din.readUnsignedByte()
                    len = din.readInt()
                } catch (e: EOFException) {
                    throw BackupException("The backup file is cut short or damaged.")
                }
                if (flag > 1 || len < 16 || len > CHUNK + 16) throw BackupException("The backup file is damaged.")
                val sealed = ByteArray(len)
                try { din.readFully(sealed) } catch (e: EOFException) { throw BackupException("The backup file is cut short or damaged.") }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
                c.updateAAD(aad(header, flag == 1))
                cur = try {
                    c.doFinal(sealed)
                } catch (e: AEADBadTagException) {
                    throw BackupException(if (index == 0) wrong else "The backup file is damaged.")
                }
                index++
                pos = 0
                done = flag == 1
                return true
            }

            override fun read(): Int {
                while (pos >= cur.size) if (!next()) return -1
                return cur[pos++].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= cur.size) if (!next()) return -1
                val n = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, n)
                pos += n
                return n
            }

            override fun close() = din.close()
        }
    }
}
