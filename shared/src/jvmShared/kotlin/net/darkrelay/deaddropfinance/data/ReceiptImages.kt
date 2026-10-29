package net.darkrelay.deaddropfinance.data

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What deleting rows needs from the receipt photo store: removing a receipt's files. */
fun interface ReceiptFiles {
    fun delete(receiptId: String)
}

/**
 * Receipt photos, encrypted, in the app's private files folder (never shared storage or the gallery). Each page is
 * one file, AES-256-GCM with a random nonce, its name bound in as associated data so a file can't be passed off as
 * another page. The key is random, made once and kept in SecretStore, which wraps it with its own Android Keystore
 * key, like the bank credentials. Erase everything deletes the folder and the key.
 */
class ReceiptImages(private val dir: File, private val key: () -> ByteArray) : ReceiptFiles {

    private val random = SecureRandom()

    private fun file(receiptId: String, page: Int) = File(dir, "$receiptId-$page.enc")

    fun write(receiptId: String, page: Int, bytes: ByteArray) {
        dir.mkdirs()
        val f = file(receiptId, page)
        val iv = ByteArray(IV).also { random.nextBytes(it) }
        val c = Cipher.getInstance(CIPHER)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(f.name.toByteArray())
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(MAGIC + iv + c.doFinal(bytes))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** The page's image bytes, or null if it's missing or can't be decrypted (the key is gone or it was changed). */
    fun read(receiptId: String, page: Int): ByteArray? {
        val f = file(receiptId, page)
        if (!f.exists()) return null
        return try {
            val all = f.readBytes()
            if (all.size < MAGIC.size + IV || !all.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
            val iv = all.copyOfRange(MAGIC.size, MAGIC.size + IV)
            val c = Cipher.getInstance(CIPHER)
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(TAG_BITS, iv))
            c.updateAAD(f.name.toByteArray())
            c.doFinal(all, MAGIC.size + IV, all.size - MAGIC.size - IV)
        } catch (e: Exception) {
            null
        }
    }

    fun pages(receiptId: String): Int = (dir.listFiles() ?: emptyArray()).count { it.name.startsWith("$receiptId-") && it.name.endsWith(".enc") }

    override fun delete(receiptId: String) {
        (dir.listFiles() ?: emptyArray()).filter { it.name.startsWith("$receiptId-") }.forEach { it.delete() }
    }

    /** Every stored page, as (receipt id, page). */
    fun stored(): List<Pair<String, Int>> = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".enc") }.mapNotNull { f ->
        val base = f.name.removeSuffix(".enc")
        base.substringAfterLast('-').toIntOrNull()?.let { base.substringBeforeLast('-') to it }
    }.sortedWith(compareBy({ it.first }, { it.second }))

    /** A store for [other] files under this same key: a restore stages its photos there first. */
    fun staging(other: File) = ReceiptImages(other, key)

    /** Restore: every photo here is replaced by the ones staged in [staged] (a [staging] store), which is emptied. */
    fun replaceWith(staged: File) {
        dir.mkdirs()
        (dir.listFiles() ?: emptyArray()).forEach { it.delete() }
        (staged.listFiles() ?: emptyArray()).forEach { f ->
            val to = File(dir, f.name)
            if (!f.renameTo(to)) { f.copyTo(to, overwrite = true); f.delete() }
        }
        staged.delete()
    }

    /** Removes files whose receipt is gone (a crash between deleting the row and the file, say). */
    fun sweep(keep: Set<String>) {
        (dir.listFiles() ?: emptyArray()).forEach { f ->
            val id = f.name.substringBeforeLast('-')
            if (id !in keep) f.delete()
        }
    }

    companion object {
        private val MAGIC = "DDR1".toByteArray()
        private const val IV = 12
        private const val TAG_BITS = 128
        private const val CIPHER = "AES/GCM/NoPadding"
        const val KEY_NAME = "receipts.image.key"

        /** The photo key from SecretStore, made on first use. */
        fun keyFrom(secrets: SecretStore): ByteArray = synchronized(this) {
            secrets.get(KEY_NAME)?.let { Base64.getDecoder().decode(it) }
                ?: ByteArray(32).also { SecureRandom().nextBytes(it); secrets.put(KEY_NAME, Base64.getEncoder().encodeToString(it)) }
        }
    }
}
