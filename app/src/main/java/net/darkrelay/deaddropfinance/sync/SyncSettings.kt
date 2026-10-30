package net.darkrelay.deaddropfinance.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Plain settings for setup, sorting, alerts and backup. Nothing here is a secret: they are
 * switches and times. The name (and the "sync" settings file) is from when it also held bank sync's settings (until
 * 1.3.0); renaming the file would lose every setting.
 */
class SyncSettings(private val prefs: SharedPreferences) : net.darkrelay.deaddropfinance.ui.AppSettings {

    constructor(context: Context) : this(context.getSharedPreferences("sync", Context.MODE_PRIVATE))

    /** Setup finished (or a working setup was found) on this install. */
    override var setupDone: Boolean
        get() = prefs.getBoolean("setup_done", false)
        set(v) = prefs.edit { putBoolean("setup_done", v) }

    /** Purge data ran: the next start opens the setup wizard. Cleared at Finish. */
    override var setupAfterPurge: Boolean
        get() = prefs.getBoolean("setup_after_purge", false)
        set(v) = prefs.edit { putBoolean("setup_after_purge", v) }

    /** The Home card for installs updated from 0.6.x was dismissed. */
    override var setupCardDismissed: Boolean
        get() = prefs.getBoolean("setup_card_dismissed", false)
        set(v) = prefs.edit { putBoolean("setup_card_dismissed", v) }

    /** Rule matches are sorted as they arrive instead of waiting in To sort for a tap. Off by default. */
    override var autoApprove: Boolean
        get() = prefs.getBoolean("auto_approve", false)
        set(v) = prefs.edit { putBoolean("auto_approve", v) }

    // ---- alerts (bill reminders and autopay warnings) -----------------------------------------------------------------

    /** Bill names and amounts on the lock screen. Off by default: the lock screen shows only what kind of alert it is. */
    override var alertAmountsOnLockScreen: Boolean
        get() = prefs.getBoolean("alert_lock_screen_amounts", false)
        set(v) = prefs.edit { putBoolean("alert_lock_screen_amounts", v) }

    /** The Home card asking to turn alerts on was answered (either way), so it doesn't come back. */
    override var alertsAsked: Boolean
        get() = prefs.getBoolean("alerts_asked", false)
        set(v) = prefs.edit { putBoolean("alerts_asked", v) }

    /** Bill reminders (each bill's own switch decides; this is the whole channel). On by default. */
    override var alertBills: Boolean
        get() = prefs.getBoolean("alert_bills", true)
        set(v) = prefs.edit { putBoolean("alert_bills", v) }

    /** Autopay warnings: an autopay due before payday with its envelope short. On by default. */
    override var alertAutopay: Boolean
        get() = prefs.getBoolean("alert_autopay", true)
        set(v) = prefs.edit { putBoolean("alert_autopay", v) }

    /** Reminders and warnings already sent (Reminders.Due.key, or "autopay:<bill>:<due>"), so each goes once. */
    override var reminded: Set<String>
        get() = prefs.getStringSet("reminded", emptySet())!!.toSet()
        set(v) = prefs.edit { putStringSet("reminded", v) }

    // ---- backup (this phone's own: not carried in a backup) -------------------------------------------------------

    /** When the last backup was saved (epoch millis), and the file's name, for the backup screen. */
    override var lastBackupAt: Long
        get() = prefs.getLong("last_backup_at", 0)
        set(v) = prefs.edit { putLong("last_backup_at", v) }

    override var lastBackupName: String
        get() = prefs.getString("last_backup_name", "") ?: ""
        set(v) = prefs.edit { putString("last_backup_name", v) }

    /** Every key that is set (the upgrade cleanup looks for old ones). */
    fun keys(): Set<String> = prefs.all.keys.toSet()

    /** Removes settings keys that no longer mean anything (the upgrade cleanup uses this). */
    fun removeKeys(keys: Collection<String>) = prefs.edit { keys.forEach { remove(it) } }
}
