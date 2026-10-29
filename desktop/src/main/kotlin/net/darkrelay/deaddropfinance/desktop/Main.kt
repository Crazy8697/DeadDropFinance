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
    val updates = UpdateModel()
    // the budget (the phone app's engine on the program's own encrypted file) opens in the background
    val engine = CompletableDeferred<Engine>()
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

        LaunchedEffect(Unit) {
            val e = withContext(Dispatchers.IO) { engine.await() }
            val m = AppModel(e)
            withContext(Dispatchers.IO) { m.refresh() }
            model = m
        }

        fun checkNow(manual: Boolean) {
            scope.launch { withContext(Dispatchers.IO) { updates.runCheck(manual) } }
        }

        fun quit() {
            if (engine.isCompleted) engine.getCompleted().close()
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
