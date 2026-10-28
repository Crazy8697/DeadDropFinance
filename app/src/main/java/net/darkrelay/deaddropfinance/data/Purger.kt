package net.darkrelay.deaddropfinance.data

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.room.withTransaction
import java.io.File

/**
 * Purge data (FEATURES.md): back to a fresh install's state. Every budget row goes (accounts, transactions, envelopes,
 * bills, paydays, rules, subscriptions, receipts, parked buys, the change log), receipt photos go, and every setting
 * goes back to its default. Unlike Erase everything, the database stays open and its key stays.
 */
class Purger(
    private val db: AppDatabase,
    private val secrets: SecretStore?,
    private val settings: List<SharedPreferences>,
    /** Receipt photos and the camera's capture folder: emptied. */
    private val dirs: List<File>,
) {
    suspend fun purge() {
        val sql = db.openHelper.writableDatabase
        val tables = mutableListOf<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) tables += c.getString(0) }
        db.withTransaction {
            for (t in tables) {
                when {
                    t in KEPT || t.startsWith("sqlite_") -> {}
                    t == "meta" -> sql.execSQL("DELETE FROM meta WHERE `key` != ?", arrayOf(BudgetStore.META_DEVICE_ID))
                    else -> sql.execSQL("DELETE FROM `$t`")
                }
            }
        }
        db.invalidationTracker.refreshVersionsAsync()
        for (d in dirs) d.listFiles()?.forEach { it.deleteRecursively() }
        // saved secrets: the receipt photo key goes with the photos
        secrets?.let { s -> runCatching { s.removeWhere { true } } }
        for (p in settings) p.edit(commit = true) { clear() }
    }

    companion object {
        /** The database's own bookkeeping. */
        val KEPT = setOf("room_master_table", "android_metadata")
    }
}
