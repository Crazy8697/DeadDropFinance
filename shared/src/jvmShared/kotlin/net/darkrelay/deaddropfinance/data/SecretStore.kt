package net.darkrelay.deaddropfinance.data

import java.io.File
import org.json.JSONObject

/**
 * Where secrets live: today only the receipt photo key (bank sync's SimpleFIN logins lived here until 1.3.0). They are
 * encrypted as one blob with a Keystore-wrapped key (its own Keystore key, separate from the database key's), in their
 * own file, never in the database, SharedPreferences or the change log. Secrets never travel in a backup.
 */
class SecretStore(private val file: File, private val wrapper: KeyWrapper) {

    /** The blob exists but can't be unwrapped (the Keystore key is gone). It is never replaced or deleted. */
    class Unavailable(message: String) : Exception(message)

    private fun load(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(String(wrapper.unwrap(file.readBytes()), Charsets.UTF_8))
        } catch (t: Throwable) {
            throw Unavailable("the saved secrets can't be unlocked on this phone")
        }
    }

    private fun save(o: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(wrapper.wrap(o.toString().toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "the secrets couldn't be saved" }
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

    companion object {
        const val FILE_NAME = "secrets.bin"
    }
}
