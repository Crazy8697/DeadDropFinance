package net.darkrelay.deaddropfinance

import android.content.Intent
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
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
    private var erased by mutableStateOf(false)
    /** Rises each time a spend alert opens the app, so it goes to Activity (To sort). */
    private var openActivity by mutableIntStateOf(0)
    private var permissionDone: ((Boolean) -> Unit)? = null
    /** Images shared to the app, to scan as a receipt. */
    private var shared by mutableStateOf<net.darkrelay.deaddropfinance.ui.SharedReceipt?>(null)
    private var photoDone: ((Uri?) -> Unit)? = null
    private var captureFile: java.io.File? = null
    private var fileDone: ((Uri?) -> Unit)? = null

    /** The camera app writes the photo to a file in the app's cache (no camera permission); it's deleted once read. */
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val done = photoDone ?: return@registerForActivityResult
        photoDone = null
        val f = captureFile
        if (ok && f != null && f.length() > 0) done(Uri.fromFile(f)) else { f?.delete(); done(null) }
    }

    private val receiptPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val done = fileDone ?: return@registerForActivityResult
        fileDone = null
        done(uri)
    }

    private val scanner by lazy {
        net.darkrelay.deaddropfinance.scan.ReceiptScanner(this, app.receiptImages) { net.darkrelay.deaddropfinance.scan.TesseractReader(this) }
    }
    private val app get() = application as DdfApp

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val done = permissionDone ?: return@registerForActivityResult
        permissionDone = null
        if (!ok && !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
        done(ok && alerts.allowed())
    }
    private val alerts by lazy { net.darkrelay.deaddropfinance.alerts.SpendAlerts(this) }

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
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_ACTIVITY) openActivity++
        takeShared(intent)
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
                            erased -> net.darkrelay.deaddropfinance.ui.ErasedScreen(onOpen = { Browsers.open(this@MainActivity, it) }, onStartOver = ::startOver)
                            o is DatabaseOpener.Opened.Ok && sync != null -> AppRoot(
                                o.store, sync!!, platform,
                                startupUpdateCheck = Updates.checkAtStartup(this@MainActivity) && !Updates.startupCheckDone.also { Updates.startupCheckDone = true },
                                openActivity = openActivity,
                                shared = shared,
                            )
                            else -> {}
                        }
                    }
                }
            }
        }
        if (locked) promptUnlock()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(EXTRA_OPEN) == OPEN_ACTIVITY) openActivity++
        takeShared(intent)
    }

    /** A photo or PDF shared from another app: scanned as a receipt once the app is open and unlocked. */
    private fun takeShared(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        if (uris.isNotEmpty()) shared = net.darkrelay.deaddropfinance.ui.SharedReceipt(uris.take(10), (shared?.n ?: 0) + 1)
    }

    override fun onResume() {
        super.onResume()
        if (!app.lock.locked) clearAlerts()
    }

    /** The app is open and unlocked: the spend alerts showing have been seen. */
    private fun clearAlerts() {
        sync?.let { alerts.clear(it.settings) }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** After an erase: open a new, empty database, as on a fresh install (the setup wizard comes up). */
    private fun startOver() {
        erased = false
        applyHide()
        lifecycleScope.launch {
            opened = withContext(Dispatchers.IO) { app.open() }
            sync = withContext(Dispatchers.IO) { app.syncService() }
        }
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
                        clearAlerts()
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

        override fun eraseEverything() {
            lifecycleScope.launch {
                sync = null
                opened = null
                withContext(Dispatchers.IO) { app.eraseEverything() }
                erased = true
            }
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

        override fun notificationsAllowed() = alerts.allowed()

        override fun takeReceiptPhoto(done: (Uri?) -> Unit) {
            val dir = app.captureDir().apply { mkdirs() }
            val f = java.io.File(dir, "capture-${java.util.UUID.randomUUID()}.jpg")
            captureFile = f
            photoDone = done
            val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.capture", f)
            try {
                camera.launch(uri)
            } catch (e: Exception) {
                photoDone = null; f.delete(); done(null)
            }
        }

        override fun pickReceiptFile(done: (Uri?) -> Unit) {
            fileDone = done
            receiptPicker.launch(arrayOf("image/*", "application/pdf"))
        }

        override suspend fun scanReceipt(uri: Uri, receiptId: String, firstPage: Int) = withContext(Dispatchers.Default) {
            try {
                scanner.scan(uri, receiptId, firstPage)
            } finally {
                // the camera's own file is the only copy in the clear: gone as soon as it's read
                if (uri.scheme == "file") uri.path?.let { java.io.File(it).delete() }
            }
        }

        override suspend fun receiptPage(receiptId: String, page: Int): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
            val bytes = app.receiptImages.read(receiptId, page) ?: return@withContext null
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }

        override val receiptFiles: net.darkrelay.deaddropfinance.data.ReceiptFiles get() = app.receiptImages

        override fun sweepReceiptFiles(keep: Set<String>) {
            runCatching { app.receiptImages.sweep(keep) }
            runCatching { app.captureDir().listFiles()?.forEach { it.delete() } }
        }

        override fun requestNotifications(done: (Boolean) -> Unit) {
            if (alerts.allowed()) { done(true); return }
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // allowed, but notifications are switched off for the app in Android's settings
                openNotificationSettings()
                done(false)
                return
            }
            permissionDone = done
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

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
        /** A spend alert opens the app here. */
        const val EXTRA_OPEN = "open"
        const val OPEN_ACTIVITY = "activity"
    }
}

