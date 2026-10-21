package net.darkrelay.deaddropfinance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Stage 5: the app shows its name and version, and checks GitHub for a newer release. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<UpdateCheck.Release?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var atStartup by remember { mutableStateOf(Updates.checkAtStartup(ctx)) }
    val latest = stringResource(R.string.update_latest)
    val failed = stringResource(R.string.update_failed)

    LaunchedEffect(Unit) {
        if (Updates.startupCheckDone || !Updates.checkAtStartup(ctx)) return@LaunchedEffect
        Updates.startupCheckDone = true
        delay(Updates.STARTUP_DELAY_MS)
        Updates.check { r ->
            // the startup check is silent on failure and respects Skip and Later
            if (r is UpdateCheck.Result.Available && !Updates.laterThisLaunch &&
                UpdateCheck.shouldOffer(r.release, Updates.skipped(ctx), manual = false)
            ) pending = r.release
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        pending?.let { release ->
            UpdateBar(
                version = release.version,
                onNotes = { Browsers.open(ctx, release.notesUrl) },
                onDownload = { Browsers.open(ctx, UpdateCheck.DOWNLOAD_URL) },
                onSkip = { Updates.skip(ctx, release.version); pending = null },
                onLater = { Updates.laterThisLaunch = true; pending = null },
            )
        } ?: Spacer(Modifier.height(1.dp))

        NameAndVersion(BuildConfig.VERSION_NAME)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.update_at_startup), modifier = Modifier.padding(end = 12.dp))
                Switch(checked = atStartup, onCheckedChange = { atStartup = it; Updates.setCheckAtStartup(ctx, it) })
            }
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    message = null
                    Updates.check { r ->
                        checking = false
                        // a manual check ignores Skip and Later, and always says something
                        when (r) {
                            is UpdateCheck.Result.Available -> { Updates.laterThisLaunch = false; pending = r.release }
                            UpdateCheck.Result.UpToDate -> message = latest
                            UpdateCheck.Result.Failed -> message = failed
                        }
                    }
                },
            ) { Text(stringResource(if (checking) R.string.update_checking else R.string.update_check_now)) }
            Text(message ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun UpdateBar(version: String, onNotes: () -> Unit, onDownload: () -> Unit, onSkip: () -> Unit, onLater: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.update_available, version), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onNotes) { Text(stringResource(R.string.update_notes)) }
                TextButton(onClick = onDownload) { Text(stringResource(R.string.update_download)) }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip)) }
                TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
            }
        }
    }
}

@Composable
fun NameAndVersion(versionName: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.version_label, versionName),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
