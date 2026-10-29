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

    private fun save() {
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

        /** %APPDATA%\DeadDropFinance (the user's roaming profile), or ~/.deaddropfinance off Windows. */
        fun defaultDir(): File {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            return if (appData != null) File(appData, "DeadDropFinance") else File(System.getProperty("user.home"), ".deaddropfinance")
        }
    }
}
