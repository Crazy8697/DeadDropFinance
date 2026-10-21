package net.darkrelay.deaddropfinance.lock

import android.app.KeyguardManager
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** When the app should lock: pure, so the timing is unit tested. */
object LockPolicy {
    /** Timeout choices in minutes; 0 means "immediately". */
    val TIMEOUTS = listOf(0, 1, 5, 15, 30)
    const val DEFAULT_TIMEOUT = 5

    /** True when coming back to the foreground after [backgroundedAt] should lock. */
    fun shouldLockOnReturn(enabled: Boolean, deviceSecure: Boolean, backgroundedAt: Long?, now: Long, timeoutMinutes: Int): Boolean {
        if (!enabled || !deviceSecure || backgroundedAt == null) return false
        if (timeoutMinutes <= 0) return true
        return now - backgroundedAt >= timeoutMinutes * 60_000L
    }

    /** A cold start always locks when app lock is on and can work. */
    fun shouldLockOnColdStart(enabled: Boolean, deviceSecure: Boolean) = enabled && deviceSecure
}

/** App lock and hide screen settings, stored locally on the phone (not in the budget, not synced). */
class LockSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lock", Context.MODE_PRIVATE)

    var lockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK, true)
        set(v) = prefs.edit { putBoolean(KEY_LOCK, v) }

    var timeoutMinutes: Int
        get() = prefs.getInt(KEY_TIMEOUT, LockPolicy.DEFAULT_TIMEOUT).takeIf { it in LockPolicy.TIMEOUTS } ?: LockPolicy.DEFAULT_TIMEOUT
        set(v) = prefs.edit { putInt(KEY_TIMEOUT, v) }

    /** Off by default: it also blanks screen mirroring tools the user may rely on. */
    var hideScreen: Boolean
        get() = prefs.getBoolean(KEY_HIDE, false)
        set(v) = prefs.edit { putBoolean(KEY_HIDE, v) }

    companion object {
        const val KEY_LOCK = "lock_enabled"
        const val KEY_TIMEOUT = "lock_timeout_minutes"
        const val KEY_HIDE = "hide_screen"
    }
}

/**
 * Watches the whole app go to the background and come back, and decides when it's locked. [onLockedChanged]
 * gets the new state on the main thread.
 */
class AppLock(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) : DefaultLifecycleObserver {
    val settings = LockSettings(context)
    private var backgroundedAt: Long? = null
    var locked: Boolean = LockPolicy.shouldLockOnColdStart(settings.lockEnabled, deviceSecure())
        private set
    var onLockedChanged: (Boolean) -> Unit = {}

    /** The phone has a PIN, pattern, password or biometric lock; without one, app lock can't work. */
    fun deviceSecure(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true

    fun attach() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

    /** The app is opening the camera or a file picker for the user: coming back from it soon isn't leaving the app. */
    private var expecting = false

    fun expectReturn() { expecting = true }

    private companion object {
        /** Longer than this in the camera or a picker, and the usual lock applies. */
        const val PICKER_GRACE_MS = 10L * 60 * 1000
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = clock()
    }

    override fun onStart(owner: LifecycleOwner) {
        val away = backgroundedAt?.let { clock() - it }
        // back from the camera or a picker within PICKER_GRACE_MS: no lock, so the photo or file isn't lost
        val fromPicker = expecting && away != null && away < PICKER_GRACE_MS
        if (!fromPicker && LockPolicy.shouldLockOnReturn(settings.lockEnabled, deviceSecure(), backgroundedAt, clock(), settings.timeoutMinutes)) {
            set(true)
        }
        expecting = false
        backgroundedAt = null
    }

    fun unlocked() = set(false)

    private fun set(v: Boolean) {
        if (locked != v) {
            locked = v
            onLockedChanged(v)
        }
    }
}
