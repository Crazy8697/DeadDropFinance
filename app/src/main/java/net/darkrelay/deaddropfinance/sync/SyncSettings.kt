package net.darkrelay.deaddropfinance.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Plain settings for bank sync and setup. Nothing here is a secret: they are switches and times.
 */
class SyncSettings(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("sync", Context.MODE_PRIVATE))

    var backgroundEnabled: Boolean
        get() = prefs.getBoolean("background", true)
        set(v) = prefs.edit { putBoolean("background", v) }

    var intervalHours: Int
        get() = prefs.getInt("interval_hours", DEFAULT_INTERVAL_HOURS).takeIf { it in INTERVALS } ?: DEFAULT_INTERVAL_HOURS
        set(v) = prefs.edit { putInt("interval_hours", v.takeIf { it in INTERVALS } ?: DEFAULT_INTERVAL_HOURS) }

    /** Setup finished (or a working setup was found) on this install. */
    var setupDone: Boolean
        get() = prefs.getBoolean("setup_done", false)
        set(v) = prefs.edit { putBoolean("setup_done", v) }

    /** The Home card for installs updated from 0.6.x was dismissed. */
    var setupCardDismissed: Boolean
        get() = prefs.getBoolean("setup_card_dismissed", false)
        set(v) = prefs.edit { putBoolean("setup_card_dismissed", v) }

    /** Rule matches are sorted as they arrive instead of waiting in To sort for a tap. Off by default. */
    var autoApprove: Boolean
        get() = prefs.getBoolean("auto_approve", false)
        set(v) = prefs.edit { putBoolean("auto_approve", v) }

    // ---- spend alerts (notifications after a background sync) -----------------------------------------------------

    var alertSpending: Boolean
        get() = prefs.getBoolean("alert_spending", true)
        set(v) = prefs.edit { putBoolean("alert_spending", v) }

    var alertLowOrOverspent: Boolean
        get() = prefs.getBoolean("alert_low", true)
        set(v) = prefs.edit { putBoolean("alert_low", v) }

    var alertSafeToSpend: Boolean
        get() = prefs.getBoolean("alert_safe_to_spend", true)
        set(v) = prefs.edit { putBoolean("alert_safe_to_spend", v) }

    /** Payees, envelopes and amounts on the lock screen. Off by default: the lock screen shows only a count. */
    var alertAmountsOnLockScreen: Boolean
        get() = prefs.getBoolean("alert_lock_screen_amounts", false)
        set(v) = prefs.edit { putBoolean("alert_lock_screen_amounts", v) }

    /** The Home card asking to turn alerts on was answered (either way), so it doesn't come back. */
    var alertsAsked: Boolean
        get() = prefs.getBoolean("alerts_asked", false)
        set(v) = prefs.edit { putBoolean("alerts_asked", v) }

    /** New transactions already in a showing notification and not seen in the app yet: the next alert adds to them. */
    var alertIds: Set<String>
        get() = prefs.getStringSet("alert_ids", emptySet())!!.toSet()
        set(v) = prefs.edit { putStringSet("alert_ids", v) }

    fun alertSettings() = net.darkrelay.deaddropfinance.domain.Alerts.Settings(alertSpending, alertLowOrOverspent, alertSafeToSpend, alertAmountsOnLockScreen)

    fun lastAttempt(connectionId: String): Long = prefs.getLong("attempt.$connectionId", 0)
    fun setLastAttempt(connectionId: String, at: Long) = prefs.edit { putLong("attempt.$connectionId", at) }
    fun forget(connectionId: String) = prefs.edit { remove("attempt.$connectionId") }

    /** Removes settings keys that no longer mean anything (the upgrade cleanup uses this). */
    fun removeKeys(keys: Collection<String>) = prefs.edit { keys.forEach { remove(it) } }

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 6
        val INTERVALS = listOf(6, 12, 24)
        /** Syncing on app open happens at most this often per connection. */
        const val OPEN_MIN_GAP_MS = 30L * 60 * 1000
        /** Automatic syncs of a SimpleFIN connection happen at most this often (stricter than the 30 minutes above). */
        const val SIMPLEFIN_AUTO_GAP_MS = 60L * 60 * 1000
    }
}
