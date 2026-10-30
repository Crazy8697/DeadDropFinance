package net.darkrelay.deaddropfinance.backup

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The recovery key (1.6.0): 256 random bits that encrypt every backup (BackupCrypto format 2). Shown once, as 14
 * groups of 4 letters and digits (Crockford's base 32, which leaves out I, L, O and U, with a 3-byte check so a typo is
 * caught), then never again. It is never logged and never stored in plain text: the phone keeps it in its
 * Keystore-wrapped SecretStore, the program in a DPAPI-protected file. Lose it and the backups made with it are gone.
 */
object RecoveryKey {
    const val BYTES = 32
    private const val CHECK = 3
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    /** 14 groups of 4: (32 + 3) bytes is 280 bits, exactly 56 characters. */
    const val CHARS = 56

    fun generate(): ByteArray = ByteArray(BYTES).also { SecureRandom().nextBytes(it) }

    /** A public id for the key (SHA-256 of a label and the key, first 16 bytes, hex): which key a backup or an account uses. */
    fun id(key: ByteArray): String {
        require(key.size == BYTES)
        val d = MessageDigest.getInstance("SHA-256").digest("deaddrop recovery key id v1".toByteArray() + key)
        return d.copyOf(16).joinToString("") { "%02x".format(it) }
    }

    private fun check(key: ByteArray) = MessageDigest.getInstance("SHA-256").digest("deaddrop recovery key check".toByteArray() + key).copyOf(CHECK)

    /** "ABCD-EFGH-...": 14 groups of 4, for showing once. */
    fun format(key: ByteArray): String {
        require(key.size == BYTES)
        val bytes = key + check(key)
        val out = StringBuilder()
        var buf = 0
        var bits = 0
        for (b in bytes) {
            buf = (buf shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buf shr (bits - 5)) and 31])
                bits -= 5
            }
            buf = buf and ((1 shl bits) - 1)
        }
        check(bits == 0 && out.length == CHARS)
        return out.chunked(4).joinToString("-")
    }

    /**
     * Reads a typed or pasted key: spaces, dashes and case don't matter, and O, I and L are read as 0, 1 and 1. Throws
     * IllegalArgumentException with a plain reason when it isn't one.
     */
    fun parse(text: String): ByteArray {
        val s = text.uppercase().filter { !it.isWhitespace() && it != '-' }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }
        if (s.size != CHARS || s.any { it !in ALPHABET }) {
            throw IllegalArgumentException("That isn't a recovery key. It has $CHARS letters and digits, in 14 groups of 4.")
        }
        val out = java.io.ByteArrayOutputStream()
        var buf = 0
        var bits = 0
        for (c in s) {
            buf = (buf shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buf shr (bits - 8)) and 0xff)
                bits -= 8
                buf = buf and ((1 shl bits) - 1)
            }
        }
        val all = out.toByteArray()
        val key = all.copyOf(BYTES)
        if (!all.copyOfRange(BYTES, BYTES + CHECK).contentEquals(check(key))) {
            throw IllegalArgumentException("That recovery key has a typo: its check doesn't match. Compare it with the one you saved.")
        }
        return key
    }
}

/**
 * Where a device keeps its recovery keys, encrypted (the phone's SecretStore, the program's DPAPI file). [current] opens
 * new backups; [old] are earlier keys of this device, kept only to open its older backups (a device that logs in takes
 * the account's key). [unconfirmed]: the current key was made but "I saved it" wasn't tapped yet, so it is shown again
 * until it is.
 */
interface RecoveryKeyStore {
    fun current(): ByteArray?
    fun old(): List<ByteArray>
    /** Makes [key] the current one; a different current key moves to [old]. */
    fun setCurrent(key: ByteArray, unconfirmed: Boolean)
    fun unconfirmed(): Boolean
    fun confirm()
}

/** The rules over a [RecoveryKeyStore]: make one when needed, show it until it's saved, adopt the account's. */
class RecoveryKeys(private val store: RecoveryKeyStore) {
    fun current(): ByteArray? = store.current()

    fun currentId(): String? = store.current()?.let(RecoveryKey::id)

    fun old(): List<ByteArray> = store.old()

    /** This device's key, made now if it has none (then it must be shown before it's used). */
    fun ensure(): ByteArray = store.current() ?: RecoveryKey.generate().also { store.setCurrent(it, unconfirmed = true) }

    /** The key to show: made here and not yet confirmed as saved. Null once "I saved it" was tapped. */
    fun toShow(): ByteArray? = if (store.unconfirmed()) store.current() else null

    fun confirmSaved() = store.confirm()

    /**
     * Restore (1.7.0): the key saved on this device that a backup made with [keyId] opens with: the current key (the
     * account's, once logged in) first, then this device's older keys. Null when none of them is it, and the key is
     * typed instead.
     */
    fun savedKeyFor(keyId: String?): ByteArray? {
        if (keyId == null) return null
        return (listOfNotNull(store.current()) + store.old()).firstOrNull { RecoveryKey.id(it) == keyId }
    }

    /** A device that logged in takes the account's key; its own earlier key is kept in [old]. Nothing to show: it was shown where it was made. */
    fun adopt(accountKey: ByteArray) {
        require(accountKey.size == RecoveryKey.BYTES)
        if (store.current()?.contentEquals(accountKey) == true) return
        store.setCurrent(accountKey, unconfirmed = false)
    }
}

/** For tests and as a reference: keys in memory only. */
class MemoryRecoveryKeyStore : RecoveryKeyStore {
    private var cur: ByteArray? = null
    private val olds = mutableListOf<ByteArray>()
    private var pending = false
    override fun current() = cur?.copyOf()
    override fun old() = olds.map { it.copyOf() }
    override fun setCurrent(key: ByteArray, unconfirmed: Boolean) {
        cur?.let { c -> if (!c.contentEquals(key) && olds.none { it.contentEquals(c) }) olds += c }
        cur = key.copyOf()
        pending = unconfirmed
    }
    override fun unconfirmed() = pending
    override fun confirm() { pending = false }
}
