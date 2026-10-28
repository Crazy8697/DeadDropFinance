package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The word that has to be typed to confirm Erase everything. */
const val ERASE_WORD = "ERASE"

/** Erase everything and start over: says exactly what goes, that there's no backup, and needs ERASE typed. */
@Composable
fun EraseScreen(onBack: () -> Unit, working: Boolean, onErase: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Erase everything", onBack)
        Text("This deletes everything Dead Drop Finance keeps on this phone, for good:")
        Gap()
        for (line in listOf(
            "Every account and its balance",
            "Every transaction",
            "Every envelope and what's in it",
            "Settings, including app lock and Hide screen",
        )) {
            Text("  $line", color = DdfColors.Muted)
        }
        Gap()
        Text("If you might want this back, make a backup first (More, Backup and restore).", color = DdfColors.Warning)
        Gap()
        Text("The app then starts over like a fresh install, at the setup wizard.", color = DdfColors.Muted)
        Gap(16)
        OutlinedTextField(
            value = typed, onValueChange = { typed = it.take(10) }, singleLine = true,
            label = { Text("Type $ERASE_WORD to confirm") }, modifier = Modifier.fillMaxWidth(),
        )
        Gap()
        Button(
            onClick = onErase, enabled = typed.trim() == ERASE_WORD && !working, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = DdfColors.Error, contentColor = DdfColors.Background),
        ) { Text(if (working) "Erasing..." else "Erase everything") }
        Gap(24)
    }
}

/** The word that has to be typed to confirm Purge data. */
const val PURGE_WORD = "PURGE"

/**
 * Purge data: the same confirmation as Erase everything (what goes, typed word), for a reset that keeps the database
 * open and its key.
 */
@Composable
fun PurgeScreen(onBack: () -> Unit, working: Boolean, onPurge: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Purge data", onBack)
        Text("This deletes everything Dead Drop Finance keeps on this phone, for good:")
        Gap()
        for (line in listOf(
            "Every account and its balance",
            "Every transaction",
            "Every envelope and what's in it",
            "Bills, paydays, rules and subscriptions",
            "Receipts and their photos, and parked buys",
            "Settings, including app lock and Hide screen",
        )) {
            Text("  $line", color = DdfColors.Muted)
        }
        Gap()
        Text("Only a backup file can bring it back.", color = DdfColors.Warning)
        Gap()
        Text("The app then starts over like a fresh install, at the setup wizard.", color = DdfColors.Muted)
        Gap(16)
        OutlinedTextField(
            value = typed, onValueChange = { typed = it.take(10) }, singleLine = true,
            label = { Text("Type $PURGE_WORD to confirm") }, modifier = Modifier.fillMaxWidth(),
        )
        Gap()
        Button(
            onClick = onPurge, enabled = typed.trim() == PURGE_WORD && !working, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = DdfColors.Error, contentColor = DdfColors.Background),
        ) { Text(if (working) "Purging..." else "Purge data") }
        Gap(24)
    }
}

/**
 * After an erase. [left] is what Eraser.erase() couldn't remove from this phone (empty when everything was); the
 * headline only says everything is gone when that's actually true.
 */
@Composable
fun ErasedScreen(left: List<String>, onStartOver: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(if (left.isEmpty()) "Everything is erased" else "Erase finished, but something is left", style = MaterialTheme.typography.headlineSmall)
        Gap()
        if (left.isEmpty()) {
            Text("Nothing of your budget is left on this phone.", color = DdfColors.Muted)
        } else {
            Text("This phone couldn't remove:", color = DdfColors.Warning)
            left.forEach { Text("  $it", color = DdfColors.Muted) }
        }
        Gap(24)
        Button(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
    }
}
