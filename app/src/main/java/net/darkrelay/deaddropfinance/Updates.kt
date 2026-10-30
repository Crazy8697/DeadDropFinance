package net.darkrelay.deaddropfinance

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.util.concurrent.Executors

/**
 * The Android side of the update check: settings, the background thread, and what to show. The URL and the
 * installed version come from BuildConfig: the real GitHub URL and the real versionName in every release
 * build; only a debug build can be pointed at a test server or a fake version (see app/build.gradle.kts).
 */
object Updates {

    private const val PREFS = "updates"
    private const val KEY_AT_STARTUP = "check_at_startup"
    private const val KEY_SKIPPED = "skipped_version"

    /** How long after startup the automatic check waits, so it never competes with the first screen. */
    const val STARTUP_DELAY_MS = net.darkrelay.deaddropfinance.ui.STARTUP_UPDATE_DELAY_MS

    /** The installed version the check compares against: the real versionName unless a debug build fakes it. */
    val installedVersion: String
        get() = BuildConfig.VERSION_OVERRIDE.ifEmpty { BuildConfig.VERSION_NAME }

    // per launch (process): the startup check runs at most once, Later lasts until the next launch
    @Volatile var startupCheckDone = false
    @Volatile var laterThisLaunch = false

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "update-check").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun checkAtStartup(ctx: Context) = prefs(ctx).getBoolean(KEY_AT_STARTUP, true)
    fun setCheckAtStartup(ctx: Context, on: Boolean) = prefs(ctx).edit { putBoolean(KEY_AT_STARTUP, on) }
    fun skipped(ctx: Context): String? = prefs(ctx).getString(KEY_SKIPPED, null)
    fun skip(ctx: Context, version: String) = prefs(ctx).edit { putString(KEY_SKIPPED, version) }

    /** Run the check off the main thread; [done] gets the result on the main thread. Never throws. */
    fun check(done: (UpdateCheck.Result) -> Unit) {
        try {
            worker.execute {
                val result = UpdateClient.check(BuildConfig.UPDATE_URL, installedVersion)
                main.post { runCatching { done(result) } }
            }
        } catch (t: Throwable) {
            main.post { runCatching { done(UpdateCheck.Result.Failed) } }
        }
    }
}
