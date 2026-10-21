package net.darkrelay.deaddropfinance.data

import java.io.File
import org.json.JSONObject

/**
 * Where bank credentials live: Teller's certificate and private key, Teller access tokens and SimpleFIN access
 * URLs. They are encrypted as one blob with a Keystore-wrapped key (its own Keystore key, separate from the
 * database key's), in their own file, never in the database, SharedPreferences or the change log. Nothing reads
 * a secret back out for display: screens only get [hint], the last four characters.
 *
 * Secrets are their own category on purpose: Stage 12 (backup) decides whether they travel; today nothing does.
 */
class SecretStore(private val file: File, private val wrapper: KeyWrapper) {

    /** The blob exists but can't be unwrapped (the Keystore key is gone). It is never replaced or deleted. */
    class Unavailable(message: String) : Exception(message)

    private fun load(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(String(wrapper.unwrap(file.readBytes()), Charsets.UTF_8))
        } catch (t: Throwable) {
            throw Unavailable("the saved bank credentials can't be unlocked on this phone")
        }
    }

    private fun save(o: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(wrapper.wrap(o.toString().toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "the credentials couldn't be saved" }
        }
    }

    @Synchronized fun get(name: String): String? = load().optString(name, "").ifEmpty { null }

    @Synchronized fun has(name: String): Boolean = load().has(name)

    @Synchronized fun put(name: String, value: String) = save(load().put(name, value))

    @Synchronized fun remove(name: String) = save(load().also { it.remove(name) })

    @Synchronized fun removeWhere(match: (String) -> Boolean) {
        val o = load()
        val gone = o.keys().asSequence().filter(match).toList()
        if (gone.isEmpty()) return
        gone.forEach { o.remove(it) }
        save(o)
    }

    /** The names only, never values. */
    @Synchronized fun names(): Set<String> = load().keys().asSequence().toSet()

    /** A masked hint for the screen. The value itself is never shown after entry. */
    fun hint(name: String): String? = get(name)?.let { "..." + it.takeLast(4) }

    companion object {
        const val TELLER_CERT = "teller.certificate"
        const val TELLER_KEY = "teller.private_key"
        fun tellerToken(connectionId: String) = "teller.token.$connectionId"
        fun simplefinUrl(connectionId: String) = "simplefin.url.$connectionId"
        fun forConnection(connectionId: String): (String) -> Boolean = { it.endsWith(".$connectionId") }
        const val FILE_NAME = "secrets.bin"
    }
}
