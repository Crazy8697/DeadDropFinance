package net.darkrelay.deaddropfinance.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * Plain settings for bank sync and setup. Nothing here is a secret: the Teller application ID identifies the
 * user's Teller app (it's what Teller Connect is opened with) and the rest are switches and times.
 */
class SyncSettings(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("sync", Context.MODE_PRIVATE))

    var backgroundEnabled: Boolean
        get() = prefs.getBoolean("background", true)
        set(v) = prefs.edit().putBoolean("background", v).apply()

    var intervalHours: Int
        get() = prefs.getInt("interval_hours", DEFAULT_INTERVAL_HOURS).takeIf { it in INTERVALS } ?: DEFAULT_INTERVAL_HOURS
        set(v) = prefs.edit().putInt("interval_hours", v.takeIf { it in INTERVALS } ?: DEFAULT_INTERVAL_HOURS).apply()

    var tellerAppId: String?
        get() = prefs.getString("teller_app_id", null)
        set(v) = prefs.edit().putString("teller_app_id", v).apply()

    var tellerEnvironment: String
        get() = prefs.getString("teller_environment", null)?.takeIf { it in ENVIRONMENTS } ?: "development"
        set(v) = prefs.edit().putString("teller_environment", v.takeIf { it in ENVIRONMENTS } ?: "development").apply()

    /** Setup finished (or a working setup was found) on this install. */
    var setupDone: Boolean
        get() = prefs.getBoolean("setup_done", false)
        set(v) = prefs.edit().putBoolean("setup_done", v).apply()

    /** The Home card for installs updated from 0.6.x was dismissed. */
    var setupCardDismissed: Boolean
        get() = prefs.getBoolean("setup_card_dismissed", false)
        set(v) = prefs.edit().putBoolean("setup_card_dismissed", v).apply()

    fun lastAttempt(connectionId: String): Long = prefs.getLong("attempt.$connectionId", 0)
    fun setLastAttempt(connectionId: String, at: Long) = prefs.edit().putLong("attempt.$connectionId", at).apply()
    fun forget(connectionId: String) = prefs.edit().remove("attempt.$connectionId").apply()

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 6
        val INTERVALS = listOf(6, 12, 24)
        val ENVIRONMENTS = listOf("sandbox", "development", "production")
        /** Syncing on app open happens at most this often per connection. */
        const val OPEN_MIN_GAP_MS = 30L * 60 * 1000
        /** Automatic syncs of a SimpleFIN connection happen at most this often (stricter than the 30 minutes above). */
        const val SIMPLEFIN_AUTO_GAP_MS = 60L * 60 * 1000
    }
}
