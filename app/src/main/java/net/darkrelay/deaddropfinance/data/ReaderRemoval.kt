package net.darkrelay.deaddropfinance.data

import net.darkrelay.deaddropfinance.sync.SyncSettings

/**
 * Upgrade cleanup for the bank notification reader and its spend alerts, removed in 1.5.0. It removes what the reader
 * left behind: its settings (reading on or off, the picked apps, the spend alert switches and the list of alerts
 * showing), its picked apps with their templates and last read amount (the `notif_app` table, emptied but kept so the
 * database schema doesn't change), and its three notification channels. Transactions it logged are never touched:
 * they stay as ordinary transactions.
 *
 * It runs on every start (DdfApp.settings) and does nothing when there's nothing left to remove, so a second run,
 * or one after an older backup is restored, is safe. Nothing financial is logged.
 */
class ReaderRemoval(
    private val dao: BudgetDao,
    private val settings: SyncSettings,
    /** Deletes a notification channel by its id (NotificationManager.deleteNotificationChannel on the phone). */
    private val deleteChannel: (String) -> Unit = {},
) {
    /** How many picked apps were removed. */
    suspend fun run(): Int {
        val apps = dao.notifAppCount()
        if (apps > 0) dao.clearNotifApps()
        val left = settings.keys().intersect(OLD_SETTINGS.toSet())
        if (left.isNotEmpty()) settings.removeKeys(left)
        for (c in OLD_CHANNELS) runCatching { deleteChannel(c) }
        return apps
    }

    companion object {
        /** The reader's settings keys in the "sync" settings file (up to 1.4.x). */
        val OLD_SETTINGS = listOf("notif_reading", "notif_packages", "alert_spending", "alert_low", "alert_safe_to_spend", "alert_ids")
        /** The reader's notification channels: New spending, Envelope warnings and Notification reading. */
        val OLD_CHANNELS = listOf("spending", "warnings", "reading")
    }
}
