package net.darkrelay.deaddropfinance.data

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

/**
 * Erase everything: the encrypted database (with its -wal, -shm and -journal files), the keys folder (the wrapped
 * database key and the saved-secrets file), both Android Keystore keys, every settings file, and the scheduled
 * work. Afterwards the app starts like a fresh install. The database must be closed first. There's no backup
 * yet, so nothing can bring any of it back.
 */
class Eraser(
    private val databaseFile: File,
    private val keysDir: File,
    private val keystoreAliases: List<String>,
    private val deleteKeystoreKey: (String) -> Unit,
    private val settings: List<SharedPreferences>,
    private val cancelScheduledWork: () -> Unit,
    /** Receipt photos, the text recognition folder, the camera's capture folder. */
    private val extraDirs: List<File> = emptyList(),
) {
    /** Everything that's left afterwards (should be empty): file paths and key aliases that couldn't be removed. */
    fun erase(): List<String> {
        val left = mutableListOf<String>()
        runCatching { cancelScheduledWork() }
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val f = File(databaseFile.path + suffix)
            if (f.exists() && !f.delete()) left += f.name
        }
        if (keysDir.exists() && !keysDir.deleteRecursively()) left += keysDir.name
        for (d in extraDirs) if (d.exists() && !d.deleteRecursively()) left += d.name
        for (alias in keystoreAliases) {
            try { deleteKeystoreKey(alias) } catch (t: Throwable) { left += alias }
        }
        for (p in settings) p.edit(commit = true) { clear() }
        return left
    }
}
