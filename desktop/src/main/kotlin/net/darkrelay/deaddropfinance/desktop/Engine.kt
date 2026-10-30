package net.darkrelay.deaddropfinance.desktop

import androidx.room.useReaderConnection
import java.io.File
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.domain.BudgetActions

/**
 * The Dead Drop Finance engine on the desktop: the phone app's own logic (the shared module) on the program's own
 * encrypted budget database in %APPDATA%\DeadDropFinance, separate from the phone until sync exists.
 */
class Engine private constructor(val opened: DesktopDatabase.Opened) {

    val actions: BudgetActions? = (opened as? DesktopDatabase.Opened.Ok)?.let { BudgetActions(it.store) }

    /** What the program's data holds, in plain words, for the smoke test. Never throws. */
    suspend fun describe(): Map<String, String> {
        val ok = opened as? DesktopDatabase.Opened.Ok
            ?: return mapOf("db" to "unavailable", "dbReason" to (opened as DesktopDatabase.Opened.Unavailable).reason)
        return try {
            val version = ok.store.db.useReaderConnection { c -> c.usePrepared("PRAGMA user_version") { it.step(); it.getLong(0) } }
            mapOf(
                "db" to "ok",
                "dbFile" to ok.file.absolutePath,
                "dbEncrypted" to (!DesktopDatabase.isPlain(ok.file)).toString(),
                "dbMigrated" to ok.migrated.toString(),
                "dbSchema" to version.toString(),
                "envelopes" to ok.store.dao.envelopesNow().size.toString(),
                "categories" to ok.store.dao.categoriesNow().size.toString(),
            )
        } catch (e: Exception) {
            mapOf("db" to "unavailable")
        }
    }

    fun close() {
        (opened as? DesktopDatabase.Opened.Ok)?.store?.db?.close()
    }

    companion object {
        /**
         * Opens the program's budget and makes sure its built-in rows exist, as the phone app does when it starts
         * (the system envelopes and the starter categories). A budget that can't be opened is reported, never
         * deleted or replaced.
         */
        suspend fun start(dir: File = Settings.defaultDir(), clock: () -> Long = System::currentTimeMillis): Engine {
            val opened = DesktopDatabase.open(dir, clock)
            if (opened is DesktopDatabase.Opened.Ok) {
                try {
                    opened.store.ensureSystemEnvelopes()
                    opened.store.ensureCategories()
                    // amounts show in the budget's currency (0.12.0)
                    opened.store.applyCurrency()
                } catch (e: Exception) {
                    opened.store.db.close()
                    return Engine(DesktopDatabase.Opened.Unavailable("the budget database can't be opened"))
                }
            }
            return Engine(opened)
        }
    }
}
