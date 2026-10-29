package net.darkrelay.deaddropfinance.data

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.io.File
import java.security.SecureRandom

/** Protects and unprotects the database key. The real one is Windows DPAPI; tests can use a stand-in. */
interface KeyProtector {
    fun protect(plain: ByteArray): ByteArray
    fun unprotect(blob: ByteArray): ByteArray
}

/**
 * Windows DPAPI for the current user (CryptProtectData without a machine-wide flag): only this Windows account can
 * unwrap the key, and the blob holds no key in the clear. A fixed entropy value ties the blob to this purpose.
 */
object Dpapi : KeyProtector {
    private val entropy = "DeadDropFinance budget key v1".toByteArray(Charsets.US_ASCII)
    override fun protect(plain: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(plain, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Dead Drop Finance budget key", null)
    override fun unprotect(blob: ByteArray): ByteArray =
        Crypt32Util.cryptUnprotectData(blob, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
}

/**
 * The desktop budget's database key: 32 random bytes, kept in [file] (%APPDATA%\DeadDropFinance\budget.key) only as a
 * DPAPI blob after a short header. Never next to the exe, never logged, printed or written anywhere in the clear.
 */
class DesktopKey(val file: File, private val protector: KeyProtector = Dpapi) {

    sealed class Result {
        /** [key]: the caller's copy; wipe it when done. */
        class Ok(val key: ByteArray) : Result()
        data class Unavailable(val reason: String) : Result()
    }

    /** The existing key. Missing or unreadable: Unavailable (a new key would make the budget unreadable for good). */
    fun load(): Result {
        if (!file.isFile) return Result.Unavailable("the budget's key file is missing")
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                return Result.Unavailable("the budget's key file isn't readable")
            }
            val key = protector.unprotect(bytes.copyOfRange(MAGIC.size, bytes.size))
            if (key.size != KEY_BYTES) {
                key.fill(0)
                Result.Unavailable("the budget's key file isn't readable")
            } else {
                Result.Ok(key)
            }
        } catch (e: Exception) {
            Result.Unavailable("the budget's key can't be unlocked by this Windows account")
        }
    }

    /** A new key, written before any database uses it. Refuses to replace an existing key file. */
    fun create(): Result {
        if (file.exists()) return Result.Unavailable("a key file is already there")
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(MAGIC + protector.protect(key))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                key.fill(0)
                return Result.Unavailable("the budget's key couldn't be saved")
            }
            Result.Ok(key)
        } catch (e: Exception) {
            key.fill(0)
            Result.Unavailable("the budget's key couldn't be saved")
        }
    }

    companion object {
        const val KEY_BYTES = 32
        private val MAGIC = "DDFKEY1\n".toByteArray(Charsets.US_ASCII)

        /** SQLCipher's raw-key form, as the phone uses it: x'<64 hex>'. */
        fun rawKeyText(key: ByteArray): String = buildString(3 + key.size * 2) {
            append("x'")
            key.forEach { append("0123456789abcdef"[(it.toInt() shr 4) and 0xF]); append("0123456789abcdef"[it.toInt() and 0xF]) }
            append('\'')
        }
    }
}
