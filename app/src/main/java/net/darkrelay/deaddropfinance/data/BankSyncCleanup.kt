package net.darkrelay.deaddropfinance.data

import net.darkrelay.deaddropfinance.sync.SyncSettings

/**
 * Upgrade cleanup for bank sync, removed in 1.3.0 (and Teller, removed in 0.7.1). The database part is the 11 to 12
 * migration; this removes what lives outside the database: the saved SimpleFIN (and Teller) logins in SecretStore,
 * bank sync's settings, and the background sync job. Accounts and transactions are never touched here.
 *
 * It runs on every start and does nothing when there's nothing to remove. Nothing financial is logged.
 */
class BankSyncCleanup(
    private val secrets: SecretStore,
    private val settings: SyncSettings,
    /** Cancels the old periodic job by its unique name (WorkManager on the phone). */
    private val cancelWork: (String) -> Unit = {},
) {
    /** How many saved logins were removed. */
    fun run(): Int {
        val gone = try {
            val names = secrets.names().filter { n -> SECRET_PREFIXES.any { n.startsWith(it) } }
            if (names.isNotEmpty()) secrets.removeWhere { n -> SECRET_PREFIXES.any { n.startsWith(it) } }
            names.size
        } catch (e: SecretStore.Unavailable) {
            // unreadable: left as it is (it's never overwritten)
            0
        }
        settings.removeKeys(OLD_SETTINGS + settings.keys().filter { it.startsWith("attempt.") })
        runCatching { cancelWork(SYNC_WORK) }
        return gone
    }

    companion object {
        val SECRET_PREFIXES = listOf("simplefin.", "teller.")
        val OLD_SETTINGS = listOf("background", "interval_hours", "teller_app_id", "teller_environment")
        /** The unique name the background sync job was scheduled under (up to 1.2.3). */
        const val SYNC_WORK = "ddf-bank-sync"
    }
}
