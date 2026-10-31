package net.darkrelay.deaddropfinance.desktop

import java.io.File
import java.util.Properties

/**
 * The program's own settings, in %APPDATA%\DeadDropFinance\settings.properties. Nothing is ever written next to the
 * exe, and uninstalling leaves this folder alone. A missing or unreadable file means the defaults; a failed save is
 * ignored (the setting then lasts until the program closes).
 */
class Settings(val dir: File) {

    private val file = File(dir, "settings.properties")
    private val props = Properties()

    init {
        try {
            if (file.isFile) file.inputStream().use { props.load(it) }
        } catch (e: Exception) {
            props.clear()
        }
    }

    var checkUpdatesAtStartup: Boolean
        get() = props.getProperty(KEY_CHECK_UPDATES)?.trim()?.lowercase() != "false"
        set(value) {
            props.setProperty(KEY_CHECK_UPDATES, value.toString())
            save()
        }

    /** A setting the phone's screens keep (0.13.0), as a property: `var x by settings.flag("key", default)`. */
    fun flag(key: String, default: Boolean) = setting(key, default, { it.trim().lowercase() == "true" }, { it.toString() })
    fun number(key: String, default: Long) = setting(key, default, { it.trim().toLongOrNull() ?: default }, { it.toString() })
    fun text(key: String, default: String) = setting(key, default, { it }, { it })
    /** A set of short keys (no commas), as one comma-separated value. */
    fun keys(key: String) = setting(key, emptySet<String>(), { v -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() }, { it.joinToString(",") })

    /** Hide screen (0.14.0): the window is kept out of screen captures. Off by default, as on the phone; Purge resets it. */
    var hideScreen: Boolean by flag(KEY_HIDE_SCREEN, false)

    /** The version Skip this version was used on (0.13.0). */
    var skippedVersion: String by text(KEY_SKIPPED, "")

    private fun <T> setting(key: String, default: T, read: (String) -> T, write: (T) -> String) =
        object : kotlin.properties.ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T =
                synchronized(props) { props.getProperty(key) }?.let { runCatching { read(it) }.getOrNull() } ?: default
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
                synchronized(props) { props.setProperty(key, write(value)) }
                save()
            }
        }

    /** Erase everything (0.13.0): every setting goes, the file too. */
    fun clearAll() {
        synchronized(props) { props.clear() }
        file.delete()
    }

    /** Purge data (0.13.0): the phone's screens' settings go back to their defaults; the program's own stay. */
    fun clearScreenSettings() {
        synchronized(props) { props.keys.map { it.toString() }.filter { it.startsWith("ui.") }.forEach { props.remove(it) } }
        save()
    }

    @Synchronized private fun save() {
        try {
            dir.mkdirs()
            val tmp = File(dir, "settings.properties.tmp")
            tmp.outputStream().use { props.store(it, "Dead Drop Finance (desktop) settings") }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            // kept in memory only
        }
    }

    companion object {
        const val KEY_CHECK_UPDATES = "checkUpdatesAtStartup"
        const val KEY_SKIPPED = "skippedVersion"
        const val KEY_HIDE_SCREEN = "ui.hideScreen"

        /** %APPDATA%\DeadDropFinance (the user's roaming profile), or ~/.deaddropfinance off Windows. */
        fun defaultDir(): File {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            return if (appData != null) File(appData, "DeadDropFinance") else File(System.getProperty("user.home"), ".deaddropfinance")
        }
    }
}
