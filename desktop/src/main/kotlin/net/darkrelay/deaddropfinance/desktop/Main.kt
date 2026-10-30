package net.darkrelay.deaddropfinance.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File
import java.net.URI
import kotlin.system.exitProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

const val PROGRAM_NAME = "Dead Drop Finance"
private const val STARTUP_CHECK_DELAY_MS = 4000L

/**
 * Dead Drop Finance for Windows.
 *   DeadDropFinance.exe                         the program
 *   DeadDropFinance.exe --update-probe <file>   no window: run the update check once, write its result to <file>
 *   DeadDropFinance.exe --smoke-test <file>     tests: open the window off screen without taking focus, write what
 *                                               it shows to <file>, and exit
 *   DeadDropFinance.exe --after-update          Setup's relaunch after an update: wait for the old copy to finish exiting
 *   DeadDropFinance.exe --instance-check <file> tests, no window: take the one-instance lock (write "first", then
 *                                               "focused" when a second launch asks) or ask the holder (write "second")
 * One instance per Windows user (0.12.3, SingleInstance): a second launch brings the open window to the front and exits.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)      // CompletableDeferred.getCompleted, after isCompleted
fun main(args: Array<String>) {
    val probe = args.indexOf("--update-probe").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    if (probe != null) {
        File(probe).writeText(describe(Updater.check()))
        exitProcess(0)
    }
    val smoke = args.indexOf("--smoke-test").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val settings = Settings(Settings.defaultDir())
    val instance = SingleInstance(settings.dir)
    val check = args.indexOf("--instance-check").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    if (check != null) {
        if (instance.claim() == SingleInstance.Claim.SECOND) { File(check).writeText("second\n"); exitProcess(0) }
        File(check).writeText("first\n")
        val asked = java.util.concurrent.CountDownLatch(1)
        instance.watch { asked.countDown() }
        val got = asked.await(60, java.util.concurrent.TimeUnit.SECONDS)
        File(check).appendText(if (got) "focused\n" else "timeout\n")
        exitProcess(0)
    }
    // the smoke test (tests, off screen) never takes the lock; everything else is one instance per user
    if (smoke == null && instance.claim(afterUpdate = "--after-update" in args) == SingleInstance.Claim.SECOND) exitProcess(0)
    val updates = UpdateModel(skipped = { settings.skippedVersion }, onSkip = { settings.skippedVersion = it })
    // the budget (the phone app's engine on the program's own encrypted file) opens in the background
    val engine = CompletableDeferred<Engine>()
    val syncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    Thread({ engine.complete(runBlocking { Engine.start(settings.dir) }) }, "engine-start").apply { isDaemon = true }.start()
    application {
        val windowState = rememberWindowState(
            size = DpSize(1400.dp, 900.dp),
            position = if (smoke != null) WindowPosition(-32000.dp, -32000.dp) else WindowPosition.PlatformDefault,
        )
        var update by remember { mutableStateOf<UpdateState>(UpdateState.Hidden) }
        var checkAtStartup by remember { mutableStateOf(settings.checkUpdatesAtStartup) }
        var model by remember { mutableStateOf(AppModel(null)) }
        val scope = rememberCoroutineScope()
        updates.onChange = { update = it }
        // bill reminders and autopay warnings as Windows notifications (0.13.0), under the phone's own settings
        val notices = remember { TrayNotices(java.awt.Toolkit.getDefaultToolkit().getImage(Thread.currentThread().contextClassLoader.getResource("deaddropfinance-256.png"))) }

        /** The model on an opened budget: its recovery key and server sync set up, as at every start. */
        suspend fun openModel(e: Engine): AppModel {
            val m = AppModel(e)
            withContext(Dispatchers.IO) { m.refresh() }
            // the recovery key (0.11.0): every backup's key, DPAPI-protected in the data folder
            val keys = net.darkrelay.deaddropfinance.backup.RecoveryKeys(DesktopRecoveryStore(settings.dir))
            if (smoke == null) m.recoveryKeys = keys
            // server sync (0.10.0): the phone's own sync code on this budget; its settings and key in the data folder
            val ok = e.opened as? net.darkrelay.deaddropfinance.data.DesktopDatabase.Opened.Ok
            if (ok != null && smoke == null) {
                val sync = net.darkrelay.deaddropfinance.serversync.ServerSync(ok.store, DesktopSyncPrefs(settings.dir), DesktopKeyVault(settings.dir), syncScope,
                    kind = "desktop", recovery = keys)
                m.server = ServerModel(sync, m, syncScope)
                // Stay logged in off (0.11.0): a close that didn't finish logging out is finished before anything shows
                withContext(Dispatchers.IO) { m.server!!.openingCheck() }
            }
            // a recovery key made here but not confirmed as saved is shown again
            if (smoke == null) m.showRecoveryKeyIfNew()
            return m
        }

        LaunchedEffect(Unit) {
            val e = withContext(Dispatchers.IO) { engine.await() }
            model = openModel(e)
        }

        // bill reminders and autopay warnings (0.13.0): checked a minute after the budget opens, then every 6 hours, as the
        // phone's job does; each goes once per bill cycle, under More, Alerts
        if (smoke == null) LaunchedEffect(model) {
            val ok = model.engine?.opened as? net.darkrelay.deaddropfinance.data.DesktopDatabase.Opened.Ok ?: return@LaunchedEffect
            delay(60_000)
            while (true) {
                withContext(Dispatchers.IO) { runCatching { ProgramAlerts.check(ok.store, settings, notices, java.time.LocalDate.now()) } }
                delay(6 * 60 * 60 * 1000L)
            }
        }

        // Erase everything and Purge data (0.13.0), after the phone's confirm screens in More
        fun erase() = scope.launch {
            model = ProgramReset.eraseNow(model, settings.dir, settings) { e -> openModel(e) }
            checkAtStartup = settings.checkUpdatesAtStartup
        }
        fun purge() = scope.launch { ProgramReset.purgeNow(model, settings) }

        fun checkNow(manual: Boolean) {
            scope.launch { withContext(Dispatchers.IO) { updates.runCheck(manual) } }
        }

        fun quit() {
            // let go of the edit lock and send what's waiting (a few seconds at most), then close the budget
            model.server?.let { s -> runBlocking { s.closing() } }
            (model.engine ?: engine.takeIf { it.isCompleted }?.getCompleted())?.close()
            notices.close()
            instance.release()
            exitApplication()
        }

        Window(
            onCloseRequest = ::quit,
            state = windowState,
            title = PROGRAM_NAME,
            icon = painterResource("deaddropfinance-256.png"),
            // the smoke test never takes focus from whatever the user is doing
            focusable = smoke == null,
        ) {
            // Windows' own title bar, dark like the program (0.9.0)
            LaunchedEffect(Unit) { TitleBar.dark(window) }
            // a second launch (0.12.3): this window comes back from the taskbar and to the front
            if (smoke == null) LaunchedEffect(Unit) {
                instance.watch {
                    java.awt.EventQueue.invokeLater {
                        windowState.isMinimized = false
                        window.toFront()
                        // Windows lets a window to the front only in some cases; a moment on top does it, then back to normal
                        window.isAlwaysOnTop = true
                        window.isAlwaysOnTop = false
                        window.requestFocus()
                    }
                }
            }
            // the edit lock follows the window (0.10.0): held while it's open and not minimized
            val server = model.server
            LaunchedEffect(server, windowState.isMinimized) {
                if (server != null) { if (windowState.isMinimized) server.hidden() else server.shown() }
            }
            val installed = UpdateModel.isInstalled()
            val shell = Shell(
                update = update,
                canInstall = updates.canInstall,
                checkAtStartup = checkAtStartup,
                onInstall = {
                    scope.launch {
                        val started = withContext(Dispatchers.IO) { updates.install() }
                        // Setup closes and replaces this copy, then starts the new one
                        if (started) quit()
                    }
                },
                onOpenPage = { openInBrowser(it) },
                onDismissUpdate = { updates.dismiss() },
                onSkipUpdate = { updates.skip() },
                onCheckNow = { checkNow(true) },
                onCheckAtStartup = {
                    checkAtStartup = it
                    settings.checkUpdatesAtStartup = it
                },
                onOpenDataFolder = { openFile(settings.dir) },
                onLicenses = if (installed) ({ openFile(File(installDir(), "THIRD-PARTY-NOTICES.md")) }) else null,
                onExit = ::quit,
                run = { block -> scope.launch(Dispatchers.IO) { block() } },
                pickCsv = {
                    val d = FileDialog(window, "Import CSV", FileDialog.LOAD).apply { setFilenameFilter { _, n -> n.lowercase().endsWith(".csv") }; file = "*.csv"; isVisible = true }
                    d.file?.let { File(d.directory, it) }
                },
                pickBackupSave = { suggested ->
                    val d = FileDialog(window, "Back up", FileDialog.SAVE).apply { file = suggested; isVisible = true }
                    d.file?.let { n -> File(d.directory, if (n.lowercase().endsWith(".ddfbak")) n else "$n.ddfbak") }
                },
                pickBackupOpen = {
                    val d = FileDialog(window, "Restore", FileDialog.LOAD).apply { setFilenameFilter { _, n -> n.lowercase().endsWith(".ddfbak") }; file = "*.ddfbak"; isVisible = true }
                    d.file?.let { File(d.directory, it) }
                },
                settings = settings,
                pickJson = {
                    val d = FileDialog(window, "Import setup", FileDialog.LOAD).apply { setFilenameFilter { _, n -> n.lowercase().endsWith(".json") }; file = "*.json"; isVisible = true }
                    d.file?.let { File(d.directory, it) }
                },
                pickSave = { suggested ->
                    val d = FileDialog(window, "Save", FileDialog.SAVE).apply { file = suggested; isVisible = true }
                    d.file?.let { File(d.directory, it) }
                },
                pickReceipt = {
                    val d = FileDialog(window, "Choose a receipt image", FileDialog.LOAD).apply {
                        setFilenameFilter { _, n -> n.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } }; isVisible = true
                    }
                    d.file?.let { File(d.directory, it) }
                },
                notices = notices,
                docs = { programDocs() },
                readDoc = { name -> programDoc(name) },
                onErase = { erase() },
                onPurge = { purge() },
            )
            DesktopApp(model, shell)
            if (smoke != null) {
                LaunchedEffect(Unit) {
                    delay(1500)
                    val data = withContext(Dispatchers.IO) { engine.await().describe() }
                    File(smoke).writeText(
                        "name=$PROGRAM_NAME\nversion=${BuildInfo.VERSION}\ntitle=${window.title}\n" +
                            "settingsDir=${settings.dir.absolutePath}\nshowing=${window.isShowing}\n" +
                            "focused=${window.isFocused}\ncheckUpdatesAtStartup=$checkAtStartup\n" +
                            "screen=${model.screen.title}\n" +
                            data.entries.joinToString("") { "${it.key}=${it.value}\n" }
                    )
                    quit()
                }
            } else if (checkAtStartup) {
                LaunchedEffect(Unit) {
                    delay(STARTUP_CHECK_DELAY_MS)
                    checkNow(false)
                }
            }
        }
    }
}

/** About, Licenses (0.13.0): the notices and license texts the installed program carries, by file name. */
private fun programDocs(): List<String> {
    if (!UpdateModel.isInstalled()) return emptyList()
    val top = listOf("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md").filter { File(installDir(), it).isFile }
    return top + (File(installDir(), "licenses").listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList())
}

private fun programDoc(name: String): String? {
    if (!UpdateModel.isInstalled()) return null
    val f = listOf(File(installDir(), name), File(File(installDir(), "licenses"), name)).firstOrNull { it.isFile } ?: return null
    return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
}

/** The installed program's folder (jpackage's launcher sets jpackage.app-path to the exe). */
private fun installDir(): File = File(System.getProperty("jpackage.app-path") ?: ".").absoluteFile.parentFile

fun describe(r: Updater.Result): String = when (r) {
    is Updater.Result.Newer -> "status=newer\nversion=${r.release.version}\ntag=${r.release.tag}\n"
    is Updater.Result.Current -> "status=current\nnewest=${r.newest ?: ""}\ninstalled=${BuildInfo.VERSION}\n"
    is Updater.Result.Failed -> "status=failed\nreason=${r.reason}\n"
}

private fun openInBrowser(url: String) {
    try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    } catch (e: Exception) {
        // nothing to do: the link is also on the release page
    }
}

private fun openFile(f: File) {
    try {
        if (Desktop.isDesktopSupported() && f.exists()) Desktop.getDesktop().open(f)
    } catch (e: Exception) {
        // nothing to do
    }
}
