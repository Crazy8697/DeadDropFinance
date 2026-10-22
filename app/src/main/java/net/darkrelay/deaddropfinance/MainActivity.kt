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
import androidx.core.net.toUri
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
    /** What Erase everything couldn't remove (empty: everything went). */
    private var erasedLeft by mutableStateOf<List<String>>(emptyList())
    /** Rises with each Purge data, so the screens start again from scratch (at the setup wizard). */
    private var generation by mutableIntStateOf(0)
    /** Rises each time the activity comes back to the front. */
    private var resumeTick by mutableIntStateOf(0)
    /** Where a notification opened the app (To sort, Bills, Home, reading setup); [OpenTo.n] rises each time. */
    private var openTo by mutableStateOf<net.darkrelay.deaddropfinance.ui.OpenTo?>(null)
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
    private var backupDone: ((Uri?) -> Unit)? = null

    private val backupSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val done = backupDone ?: return@registerForActivityResult
        backupDone = null
        done(uri)
    }

    private val backupOpener = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val done = backupDone ?: return@registerForActivityResult
        backupDone = null
        done(uri)
    }

    private var exportExampleDone: ((Boolean) -> Unit)? = null
    private val exampleSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val done = exportExampleDone ?: return@registerForActivityResult
        exportExampleDone = null
        if (uri == null) { done(false); return@registerForActivityResult }
        val ok = runCatching {
            val doc = assets.open("docs/IMPORT.md").use { String(it.readBytes(), Charsets.UTF_8) }
            val json = net.darkrelay.deaddropfinance.domain.SetupExample.extract(doc) ?: return@runCatching false
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        done(ok)
    }

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
        takeOpen(intent)
        takeShared(intent)
        net.darkrelay.deaddropfinance.alerts.BillReminderWorker.schedule(this)
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
                            erased -> net.darkrelay.deaddropfinance.ui.ErasedScreen(erasedLeft, onOpen = { Browsers.open(this@MainActivity, it) }, onStartOver = ::startOver)
                            o is DatabaseOpener.Opened.Ok && sync != null -> androidx.compose.runtime.key(generation) {
                                AppRoot(
                                    o.store, sync!!, platform,
                                    startupUpdateCheck = Updates.checkAtStartup(this@MainActivity) && !Updates.startupCheckDone.also { Updates.startupCheckDone = true },
                                    openTo = openTo,
                                    shared = shared,
                                    onTaken = { shared = null; openTo = null },
                                    resumeTick = resumeTick,
                                )
                            }
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
        takeOpen(intent)
        takeShared(intent)
    }

    private fun takeOpen(intent: Intent?) {
        val where = intent?.getStringExtra(EXTRA_OPEN) ?: return
        openTo = net.darkrelay.deaddropfinance.ui.OpenTo(where, (openTo?.n ?: 0) + 1)
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
        resumeTick++
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
        erasedLeft = emptyList()
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
            app.lock.expectReturn()
            picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel"))
        }

        override fun pickJson(onPicked: (String, String?) -> Unit) {
            pickCallback = onPicked
            app.lock.expectReturn()
            picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }

        override fun eraseEverything() {
            lifecycleScope.launch {
                sync = null
                opened = null
                erasedLeft = withContext(Dispatchers.IO) { app.eraseEverything() }
                erased = true
            }
        }

        override fun purgeData() {
            lifecycleScope.launch {
                sync = null
                // a failed purge rolls its database part back; either way the screens start again from what's there
                withContext(Dispatchers.IO) { runCatching { app.purgeData() } }
                sync = withContext(Dispatchers.IO) { app.syncService() }
                applyHide()
                shared = null
                openTo = null
                generation++
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
                app.lock.expectReturn()
                camera.launch(uri)
            } catch (e: Exception) {
                photoDone = null; f.delete(); done(null)
            }
        }

        override fun pickReceiptFile(done: (Uri?) -> Unit) {
            fileDone = done
            app.lock.expectReturn()
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

        override fun notifAccessGranted() = net.darkrelay.deaddropfinance.alerts.BankNotificationListener.accessGranted(this@MainActivity)

        override fun setNotifReading(on: Boolean) {
            val st = net.darkrelay.deaddropfinance.sync.SyncSettings(this@MainActivity)
            st.notifReading = on
            net.darkrelay.deaddropfinance.alerts.BankNotificationListener.setEnabled(this@MainActivity, on)
        }

        override fun openNotifAccess() {
            val c = net.darkrelay.deaddropfinance.alerts.BankNotificationListener.component(this@MainActivity)
            val detail = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, c.flattenToString())
            runCatching { startActivity(detail) }.onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        }

        override fun openAppInfo() {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
            }
        }

        override fun launcherApps(): List<Pair<String, String>> {
            val pm = packageManager
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return pm.queryIntentActivities(launch, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }

        override fun notifPending(): Map<String, String> {
            val inbox = net.darkrelay.deaddropfinance.alerts.BankNotificationListener.Inbox
            return inbox.packages().associateWith { inbox.get(it) ?: "" }.filterValues { it.isNotEmpty() }
        }

        override fun clearNotifPending(pkg: String) = net.darkrelay.deaddropfinance.alerts.BankNotificationListener.Inbox.clear(pkg)

        override fun docs(): List<String> = runCatching { assets.list("licenses")?.toList().orEmpty() }.getOrDefault(emptyList())

        override fun readDoc(name: String): String? = runCatching {
            if (name.contains('/') || name.contains("..")) return null
            assets.open("licenses/$name").use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrNull()

        override fun createBackupFile(name: String, done: (Uri?) -> Unit) {
            backupDone = done
            app.lock.expectReturn()
            runCatching { backupSaver.launch(name) }.onFailure { backupDone = null; done(null) }
        }

        override fun pickBackupFile(done: (Uri?) -> Unit) {
            backupDone = done
            app.lock.expectReturn()
            runCatching { backupOpener.launch(arrayOf("*/*")) }.onFailure { backupDone = null; done(null) }
        }

        override fun exportSetupExample(done: (Boolean) -> Unit) {
            exportExampleDone = done
            app.lock.expectReturn()
            runCatching { exampleSaver.launch("deaddropfinance-setup-example.json") }.onFailure { exportExampleDone = null; done(false) }
        }

        override fun displayName(uri: Uri): String = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "the file"

        override suspend fun writeBackup(uri: Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
            val store = (opened as DatabaseOpener.Opened.Ok).store
            val out = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("can't write there")
            try { app.backups(store).write(out, passphrase) } finally { passphrase.fill(' ') }
        }

        override suspend fun restoreBackup(uri: Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
            val store = (opened as DatabaseOpener.Opened.Ok).store
            val input = contentResolver.openInputStream(uri) ?: throw net.darkrelay.deaddropfinance.backup.BackupException("That file couldn't be opened.")
            try { input.use { app.backups(store).restore(it, passphrase) } } finally { passphrase.fill(' ') }
        }

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

        override fun downloadAndInstallUpdate(onEvent: (UpdateInstallEvent) -> Unit) {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val existing = verifiedUpdateFile?.takeIf { it.isFile }
            if (existing != null) {
                proceedToInstall(existing, onEvent, main)
                return
            }
            Thread {
                val dest = java.io.File(cacheDir, "update.apk")
                val result = UpdateDownloader.download(
                    UpdateCheck.DOWNLOAD_URL, UpdateCheck.SHA256_DOWNLOAD_URL, dest, UpdateClient.userAgent(Updates.installedVersion),
                ) { bytes, total ->
                    val pct = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else null
                    main.post { onEvent(UpdateInstallEvent.Downloading(pct)) }
                }
                when (result) {
                    is UpdateDownloader.Result.Ok -> {
                        verifiedUpdateFile = result.file
                        main.post { proceedToInstall(result.file, onEvent, main) }
                    }
                    is UpdateDownloader.Result.Failed -> main.post { onEvent(UpdateInstallEvent.Done(false, result.message)) }
                }
            }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }

        override fun openInstallPermissionSettings() {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
            }
        }
    }

    /** A downloaded, verified update file waiting for the user to allow installs, kept only for this process's life. */
    @Volatile private var verifiedUpdateFile: java.io.File? = null

    /** After a verified download: install it, or ask for the "install unknown apps" permission first. */
    private fun proceedToInstall(file: java.io.File, onEvent: (UpdateInstallEvent) -> Unit, main: android.os.Handler) {
        if (!packageManager.canRequestPackageInstalls()) {
            onEvent(UpdateInstallEvent.NeedsInstallPermission)
            return
        }
        Thread {
            val ok = runCatching { installViaPackageInstaller(file) }.getOrDefault(false)
            file.delete()
            verifiedUpdateFile = null
            main.post {
                onEvent(UpdateInstallEvent.Done(ok, if (ok) "Installing: finish it on the screen that opens." else "The install couldn't be started. Try again."))
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Writes [file] into a PackageInstaller session and commits it: Android's own confirmation screen takes over. */
    private fun installViaPackageInstaller(file: java.io.File): Boolean {
        val installer = packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("update", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(this, MainActivity::class.java)
            val pending = android.app.PendingIntent.getActivity(
                this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        return true
    }

    companion object {
        /** Bank exports are small; anything bigger than this isn't one. */
        const val MAX_CSV = 8 * 1024 * 1024
        /** A spend alert opens the app here. */
        const val EXTRA_OPEN = "open"
        const val OPEN_ACTIVITY = "activity"
        const val OPEN_BILLS = "bills"
        const val OPEN_HOME = "home"
        const val OPEN_READING = "reading"
    }
}

