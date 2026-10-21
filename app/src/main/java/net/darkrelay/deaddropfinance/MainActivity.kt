package net.darkrelay.deaddropfinance

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.darkrelay.deaddropfinance.data.DatabaseOpener
import net.darkrelay.deaddropfinance.ui.AppRoot
import net.darkrelay.deaddropfinance.ui.DataUnavailableScreen
import net.darkrelay.deaddropfinance.ui.DdfTheme
import net.darkrelay.deaddropfinance.ui.LockScreen
import net.darkrelay.deaddropfinance.ui.MoreState
import net.darkrelay.deaddropfinance.ui.Platform
import net.darkrelay.deaddropfinance.sync.SyncScheduler
import net.darkrelay.deaddropfinance.sync.SyncService

/**
 * The one activity. Opens the encrypted database off the main thread, shows the lock screen while locked (nothing
 * of the budget is drawn behind it), applies Hide screen, and hosts the app.
 */
class MainActivity : ComponentActivity() {

    private var opened by mutableStateOf<DatabaseOpener.Opened?>(null)
    private var locked by mutableStateOf(true)
    private var lockMessage by mutableStateOf<String?>(null)
    private var pickCallback: ((String, String?) -> Unit)? = null
    private var sync by mutableStateOf<SyncService?>(null)
    private val app get() = application as DdfApp

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val cb = pickCallback ?: return@registerForActivityResult
        pickCallback = null
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { readText(uri) }
            cb(uri.lastPathSegment?.substringAfterLast('/') ?: "file", text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHide()
        locked = app.lock.locked
        app.lock.onLockedChanged = { locked = it; if (it) promptUnlock() }
        lifecycleScope.launch {
            opened = withContext(Dispatchers.IO) { app.open() }
            sync = withContext(Dispatchers.IO) { app.syncService() }
        }
        setContent {
            DdfTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val o = opened
                        when {
                            locked -> LockScreen(onUnlock = ::promptUnlock, message = lockMessage)
                            o is DatabaseOpener.Opened.Unavailable -> DataUnavailableScreen(o.reason)
                            o is DatabaseOpener.Opened.Ok && sync != null -> AppRoot(o.store, sync!!, platform, startupUpdateCheck = Updates.checkAtStartup(this@MainActivity) && !Updates.startupCheckDone.also { Updates.startupCheckDone = true })
                            else -> {}
                        }
                    }
                }
            }
        }
        if (locked) promptUnlock()
    }

    private fun applyHide() {
        if (app.lock.settings.hideScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun promptUnlock() {
        if (!app.lock.locked) return
        lockMessage = null
        try {
            BiometricPrompt.Builder(this)
                .setTitle("Unlock Dead Drop Finance")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        app.lock.unlocked()
                        locked = false
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        lockMessage = "Not unlocked. Tap Unlock to try again."
                    }
                })
        } catch (t: Throwable) {
            lockMessage = "The phone's unlock prompt isn't available."
        }
    }

    private fun readText(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytes(MAX_CSV + 1)
            if (bytes.size > MAX_CSV) null else String(bytes, Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }

    private val platform = object : Platform {
        override fun openUrl(url: String) { Browsers.open(this@MainActivity, url) }

        override fun pickCsv(onPicked: (String, String?) -> Unit) {
            pickCallback = onPicked
            picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel"))
        }

        override fun scheduleSync(hasConnections: Boolean) {
            sync?.let { SyncScheduler.apply(this@MainActivity, it.settings, hasConnections) }
        }

        override val more: MoreState
            get() = MoreState(
                lockEnabled = app.lock.settings.lockEnabled, lockAvailable = app.lock.deviceSecure(),
                timeoutMinutes = app.lock.settings.timeoutMinutes, hideScreen = app.lock.settings.hideScreen,
                checkAtStartup = Updates.checkAtStartup(this@MainActivity), checking = false, updateMessage = null,
                version = BuildConfig.VERSION_NAME,
            )

        override fun setLock(on: Boolean) { app.lock.settings.lockEnabled = on }
        override fun setTimeout(minutes: Int) { app.lock.settings.timeoutMinutes = minutes }
        override fun setHide(on: Boolean) { app.lock.settings.hideScreen = on; applyHide() }
        override fun setCheckAtStartup(on: Boolean) = Updates.setCheckAtStartup(this@MainActivity, on)
        override fun skipVersion(version: String) = Updates.skip(this@MainActivity, version)

        override fun checkForUpdates(manual: Boolean, done: (UpdateCheck.Release?, String?) -> Unit) {
            Updates.check { r ->
                when (r) {
                    is UpdateCheck.Result.Available ->
                        if (manual || (!Updates.laterThisLaunch && UpdateCheck.shouldOffer(r.release, Updates.skipped(this@MainActivity), manual = false))) done(r.release, null)
                        else done(null, null)
                    UpdateCheck.Result.UpToDate -> done(null, if (manual) "You're on the latest version" else null)
                    UpdateCheck.Result.Failed -> done(null, if (manual) "Couldn't check for updates. Try again later." else null)
                }
            }
        }
    }

    companion object {
        /** Bank exports are small; anything bigger than this isn't one. */
        const val MAX_CSV = 8 * 1024 * 1024
    }
}

