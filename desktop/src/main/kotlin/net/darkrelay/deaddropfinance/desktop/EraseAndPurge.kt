package net.darkrelay.deaddropfinance.desktop

import java.io.File
import net.darkrelay.deaddropfinance.backup.DesktopBackups
import net.darkrelay.deaddropfinance.data.DesktopDatabase

/**
 * Erase everything and Purge data on the program (0.13.0), after the phone's own confirm screens (More), doing what
 * the phone's do with the program's files in its data folder (%APPDATA%\DeadDropFinance).
 */
object ProgramReset {

    /**
     * Erase everything: what the program keeps in [dir] goes for good: the budget and its key, the server login and
     * device key, the recovery key, receipt photos and their key, and the settings. The program's one-instance lock
     * stays (the program is running). Returns what couldn't be removed (names only).
     */
    fun erase(dir: File, settings: Settings): List<String> {
        settings.clearAll()
        val db = DesktopDatabase.FILE_NAME
        val files = listOf(
            db, "$db-wal", "$db-shm", "$db${DesktopBackups.BEFORE_RESTORE}", DesktopDatabase.KEY_FILE,
            DesktopSyncPrefs.FILE, DesktopKeyVault.FILE, DesktopRecoveryStore.FILE, "secrets.bin", "settings.properties",
        )
        val left = mutableListOf<String>()
        for (n in files) {
            val f = File(dir, n)
            if (f.exists() && !f.delete()) left += n
        }
        val receipts = File(dir, "receipts")
        if (receipts.exists() && !receipts.deleteRecursively()) left += "receipts"
        return left
    }

    /**
     * Purge data: every budget row goes (this computer's id stays), receipt photos and their key go, and the phone
     * screens' settings go back to their defaults; then the setup wizard opens, as after the phone's. The program's
     * own settings (updates) and its server login stay: purging asks to log out first, as on the phone.
     */
    suspend fun purge(ok: DesktopDatabase.Opened.Ok, settings: Settings) {
        DesktopBackups(ok.store.db, ok.file, "desktop-${BuildInfo.VERSION}").purge()
        val dir = ok.file.parentFile
        File(dir, "receipts").deleteRecursively()
        File(dir, "secrets.bin").delete()
        settings.clearScreenSettings()
        val s = ProgramAppSettings(settings)
        s.setupDone = false
        s.setupAfterPurge = true
    }

    /** Purge data from the program's More: then Home, where the phone's screens start over at the setup wizard. */
    suspend fun purgeNow(m: AppModel, settings: Settings) {
        val ok = m.engine?.opened as? DesktopDatabase.Opened.Ok ?: return
        val failed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { purge(ok, settings) }.isFailure }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { m.refresh() }
        if (failed) m.message = "Purge data didn't finish. Nothing more was removed." to true
        m.go(Screen.HOME)
        m.phoneGeneration++
    }

    /**
     * Erase everything from the program's More: logged out of the server, the budget closed and every file gone, then
     * [open] makes the model on a new empty budget in [dir], which says what couldn't be removed, if anything.
     */
    suspend fun eraseNow(old: AppModel, dir: File, settings: Settings, open: suspend (Engine) -> AppModel): AppModel {
        val left = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            old.server?.let { s -> runCatching { s.sync.logout() } }
            old.engine?.close()
            erase(dir, settings)
        }
        val m = open(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Engine.start(dir) })
        m.message = (if (left.isEmpty()) "Erased. Nothing of your budget is left on this computer."
            else "Erased, but this computer couldn't remove: ${left.joinToString(", ")}.") to left.isNotEmpty()
        return m
    }
}
