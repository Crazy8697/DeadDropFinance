package net.darkrelay.deaddropfinance.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.SecretStore

/**
 * Upgrade cleanup for installs that had a Teller connection in 0.7.0. Teller was removed in 0.7.1 (it has no public
 * signup for independent developers), so on start this removes what it left behind, the same way Disconnect with
 * "keep" does: its credentials, its connection and linked-account rows, and the provider ids on its synced
 * transactions, which stay as ordinary rows. Accounts are left as they are.
 *
 * It runs on every start and does nothing when there's nothing to remove. It reads the connection table with plain
 * SQL, since the app no longer knows the old provider's name as a value. Nothing financial is logged.
 */
class RemovedProviderCleanup(
    private val store: BudgetStore,
    private val secrets: SecretStore,
    private val settings: SyncSettings,
    /** Puts background sync in line afterwards: true if any connection is left. */
    private val reschedule: (hasConnections: Boolean) -> Unit = {},
) {
    data class Result(val connections: Int = 0, val transactions: Int = 0, val secrets: Int = 0) {
        val changed get() = connections + transactions + secrets > 0
    }

    suspend fun run(): Result {
        val ids = withContext(Dispatchers.IO) { ids("SELECT id FROM connection WHERE provider = '$PROVIDER'") }

        // synced rows stay, as ordinary ones (change-logged like any other edit)
        val cleared = store.write {
            val synced = store.dao.transactionsNow().filter { it.externalId?.startsWith(EXTERNAL_PREFIX) == true }
            synced.forEach { put(it, it.copy(externalId = null, updatedAt = now)) }
            synced.size
        }

        // this phone's own rows for the connection (not change-logged)
        if (ids.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                store.db.runInTransaction {
                    val w = store.db.openHelper.writableDatabase
                    for (id in ids) {
                        w.execSQL("DELETE FROM sync_log WHERE connection_id = ?", arrayOf(id))
                        w.execSQL("DELETE FROM linked_account WHERE connection_id = ?", arrayOf(id))
                        w.execSQL("DELETE FROM connection WHERE id = ?", arrayOf(id))
                    }
                }
            }
        }

        // its credentials; if the store can't be read it's left as it is (it's never overwritten)
        val gone = try {
            val names = secrets.names().filter { it.startsWith(SECRET_PREFIX) }
            if (names.isNotEmpty()) secrets.removeWhere { it.startsWith(SECRET_PREFIX) }
            names.size
        } catch (e: SecretStore.Unavailable) {
            0
        }

        settings.removeKeys(OLD_SETTINGS + ids.map { "attempt.$it" })
        val result = Result(ids.size, cleared, gone)
        if (result.changed) reschedule(store.sync.connectionsNow().isNotEmpty())
        return result
    }

    private fun ids(sql: String): List<String> = store.db.query(sql, null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    companion object {
        private const val PROVIDER = "TELLER"
        const val EXTERNAL_PREFIX = "teller:"
        const val SECRET_PREFIX = "teller."
        private val OLD_SETTINGS = listOf("teller_app_id", "teller_environment")
    }
}
