package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** What the backup screen shows: the last backup, a restore file picked and waiting for its password, progress. */
data class BackupState(
    val lastBackup: String? = null,
    val restoreName: String? = null,
    val working: String? = null,
    val message: String? = null,
    val error: String? = null,
)

/** A backup password has to be at least this long. */
const val BACKUP_PASSWORD_MIN = 8

/** Why a new backup password can't be used, or null when it can. */
fun backupPasswordProblem(passphrase: String, again: String): String? = when {
    passphrase.length < BACKUP_PASSWORD_MIN -> "Use at least $BACKUP_PASSWORD_MIN characters."
    passphrase != again -> "The two passwords don't match."
    else -> null
}

/**
 * Backup and restore (FEATURES.md): one encrypted file with the budget, receipt photos and settings, saved wherever
 * the user picks; restore from a picked file replaces what's in the app. Dropbox comes later.
 */
@Composable
fun BackupScreen(
    s: BackupState,
    onBack: () -> Unit,
    onBackUp: (passphrase: String) -> Unit,
    onPickRestore: () -> Unit,
    onRestore: (passphrase: String) -> Unit,
    onCancelRestore: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var restorePassword by remember(s.restoreName) { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val busy = s.working != null
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Backup and restore", onBack)
        Text(
            "One encrypted file with everything: your budget, receipt photos and settings. It needs your backup password to open.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        if (s.lastBackup != null) {
            Text("Last backup", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Text(s.lastBackup)
        }
        if (s.working != null) Text(s.working, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 8.dp))
        if (s.message != null) Text(s.message, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 8.dp))
        ErrorLine(s.error)

        SectionTitle("Back up")
        PasswordField("Backup password", passphrase) { passphrase = it }
        PasswordField("Password again", again) { again = it }
        val problem = backupPasswordProblem(passphrase, again)
        Text(
            if (tried && problem != null) problem else "At least $BACKUP_PASSWORD_MIN characters. Keep it safe: without it nobody can open the backup, you included.",
            color = if (tried && problem != null) DdfColors.Error else DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { tried = true; if (problem == null) onBackUp(passphrase) }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Back up now") }

        SectionTitle("Restore")
        Text(
            "Restoring replaces everything in the app on this phone with what's in the backup.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        if (s.restoreName == null) {
            OutlinedButton(onClick = onPickRestore, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Restore from file") }
        } else {
            Text("Restore ${s.restoreName}", modifier = Modifier.padding(top = 8.dp))
            PasswordField("That backup's password", restorePassword) { restorePassword = it }
            Row {
                Button(
                    onClick = { onRestore(restorePassword) }, enabled = !busy && restorePassword.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = DdfColors.Error, contentColor = DdfColors.Background),
                ) { Text("Replace everything and restore") }
                TextButton(onClick = onCancelRestore, enabled = !busy) { Text("Cancel") }
            }
        }

        SectionTitle("Cloud")
        Row(Modifier.padding(vertical = 4.dp)) { Text("Dropbox", Modifier.weight(1f), color = DdfColors.Muted); Text("later", color = DdfColors.Muted) }
        Gap(24)
    }
}

@Composable
private fun PasswordField(label: String, text: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}
