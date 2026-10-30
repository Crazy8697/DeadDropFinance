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

/** What the backup screen shows: the last backup, a restore file picked and waiting for its key or password, progress. */
data class BackupState(
    val lastBackup: String? = null,
    val restoreName: String? = null,
    /** The picked backup opens with a recovery key (1.6.0); false: an older backup, opened with its password. */
    val restoreWithKey: Boolean = true,
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
 * the user picks; restore from a picked file replaces what's in the app. From 1.6.0 a backup is encrypted with the
 * recovery key (made and shown once on the first backup); a backup made before 1.6.0 still restores with its password.
 */
@Composable
fun BackupScreen(
    s: BackupState,
    onBack: () -> Unit,
    onBackUp: () -> Unit,
    onPickRestore: () -> Unit,
    /** The recovery key typed (or, for an older backup, its password). */
    onRestore: (typed: String) -> Unit,
    onCancelRestore: () -> Unit,
) {
    var restoreSecret by remember(s.restoreName) { mutableStateOf("") }
    val busy = s.working != null
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Backup and restore", onBack)
        Text(
            "One encrypted file with everything: your budget, receipt photos and settings. It opens only with the recovery key.",
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
        Text(
            "Encrypted with this phone's recovery key (the account's, once logged in to a server, so it opens on any of its devices). The first backup makes the key and shows it once. Lose the key and the backups can't be opened, by anyone.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onBackUp, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Back up now") }

        SectionTitle("Restore")
        Text(
            "Restoring replaces everything in the app on this phone with what's in the backup.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        if (s.restoreName == null) {
            OutlinedButton(onClick = onPickRestore, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Restore from file") }
        } else {
            Text("Restore ${s.restoreName}", modifier = Modifier.padding(top = 8.dp))
            if (s.restoreWithKey) {
                OutlinedTextField(
                    value = restoreSecret, onValueChange = { restoreSecret = it }, label = { Text("Recovery key") }, singleLine = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("The recovery key this backup was made with: 14 groups of 4 letters and digits.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                PasswordField("That backup's password", restoreSecret) { restoreSecret = it }
                Text("This backup was made before 1.6.0, so it opens with the password it was made with.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                Button(
                    onClick = { onRestore(restoreSecret) }, enabled = !busy && restoreSecret.isNotEmpty(),
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
