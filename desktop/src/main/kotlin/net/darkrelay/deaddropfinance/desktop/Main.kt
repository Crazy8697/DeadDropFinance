package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

const val PROGRAM_NAME = "Dead Drop Finance"
private const val STARTUP_CHECK_DELAY_MS = 4000L

private val Background = Color(0xFF0B131B)
private val Panel = Color(0xFF0F1A24)
private val Accent = Color(0xFF19C2E6)
private val Muted = Color(0xFF8FA3B0)
private val ErrorText = Color(0xFFFF8A80)

/**
 * Dead Drop Finance for Windows.
 *   DeadDropFinance.exe                         the program
 *   DeadDropFinance.exe --update-probe <file>   no window: run the update check once, write its result to <file>
 *   DeadDropFinance.exe --smoke-test <file>     tests: open the window off screen without taking focus, write what
 *                                               it shows to <file>, and exit
 */
fun main(args: Array<String>) {
    val probe = args.indexOf("--update-probe").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    if (probe != null) {
        File(probe).writeText(describe(Updater.check()))
        exitProcess(0)
    }
    val smoke = args.indexOf("--smoke-test").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val settings = Settings(Settings.defaultDir())
    val model = UpdateModel()
    application {
        val windowState = rememberWindowState(
            size = DpSize(900.dp, 600.dp),
            position = if (smoke != null) WindowPosition(-32000.dp, -32000.dp) else WindowPosition.PlatformDefault,
        )
        var update by remember { mutableStateOf<UpdateState>(UpdateState.Hidden) }
        var checkAtStartup by remember { mutableStateOf(settings.checkUpdatesAtStartup) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        model.onChange = { update = it }

        fun checkNow(manual: Boolean) {
            scope.launch { withContext(Dispatchers.IO) { model.runCheck(manual) } }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = PROGRAM_NAME,
            icon = painterResource("deaddropfinance-256.png"),
            // the smoke test never takes focus from whatever the user is doing
            focusable = smoke == null,
        ) {
            MenuBar {
                Menu("Help", mnemonic = 'H') {
                    Item("Check for updates", onClick = { checkNow(true) })
                    CheckboxItem(
                        "Check for updates at startup",
                        checked = checkAtStartup,
                        onCheckedChange = {
                            checkAtStartup = it
                            settings.checkUpdatesAtStartup = it
                        },
                    )
                }
            }
            MainScreen(
                update = update,
                canInstall = model.canInstall,
                onInstall = {
                    scope.launch {
                        val started = withContext(Dispatchers.IO) { model.install() }
                        // Setup closes and replaces this copy, then starts the new one
                        if (started) exitApplication()
                    }
                },
                onOpenPage = { page -> openInBrowser(page) },
                onDismiss = { model.dismiss() },
            )
            if (smoke != null) {
                LaunchedEffect(Unit) {
                    delay(1500)
                    File(smoke).writeText(
                        "name=$PROGRAM_NAME\nversion=${BuildInfo.VERSION}\ntitle=${window.title}\n" +
                            "settingsDir=${settings.dir.absolutePath}\nshowing=${window.isShowing}\n" +
                            "focused=${window.isFocused}\ncheckUpdatesAtStartup=$checkAtStartup\n"
                    )
                    exitApplication()
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

@Composable
fun MainScreen(
    update: UpdateState,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onOpenPage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Panel)) {
        Column(Modifier.fillMaxSize().background(Background)) {
            UpdateBar(update, canInstall, onInstall, onOpenPage, onDismiss)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(PROGRAM_NAME, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("name"))
                    Spacer(Modifier.height(8.dp))
                    Text("Version ${BuildInfo.VERSION}", color = Muted, fontSize = 16.sp, modifier = Modifier.testTag("version"))
                }
            }
        }
    }
}

@Composable
fun UpdateBar(
    update: UpdateState,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onOpenPage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (update is UpdateState.Hidden) return
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 8.dp).testTag("updateBar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val text = when (update) {
            is UpdateState.Checking -> "Checking for updates..."
            is UpdateState.Available -> "Dead Drop Finance ${update.release.version} is available."
            is UpdateState.Downloading -> {
                val pct = if (update.total > 0) " ${update.done * 100 / update.total}%" else ""
                "Downloading Dead Drop Finance ${update.release.version}...$pct"
            }
            is UpdateState.Installing -> "Starting Setup for Dead Drop Finance ${update.release.version}..."
            is UpdateState.Message -> update.text
            UpdateState.Hidden -> ""
        }
        Text(
            text,
            color = if (update is UpdateState.Message && update.error) ErrorText else Color.White,
            modifier = Modifier.weight(1f).testTag("updateText"),
        )
        if (update is UpdateState.Available) {
            if (canInstall) {
                Button(onClick = onInstall, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                    modifier = Modifier.testTag("install")) { Text("Download and install") }
            }
            TextButton(onClick = { onOpenPage(update.release.page) }) { Text(if (canInstall) "What's new" else "Open the release page") }
        }
        if (update is UpdateState.Available || update is UpdateState.Message) {
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("dismiss")) { Text(if (update is UpdateState.Available) "Later" else "Close") }
        }
    }
}
