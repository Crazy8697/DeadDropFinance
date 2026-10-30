package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.backup.RecoveryKey

/**
 * The recovery key (1.6.0), shown once: in 14 groups of 4, with Copy and I saved it. It can't be dismissed any other
 * way; if the app closes first, it is shown again on the next open, and never after I saved it.
 */
@Composable
fun RecoveryKeyDialog(key: ByteArray, onCopy: (String) -> Unit, onSaved: () -> Unit) {
    val text = remember(key.contentHashCode()) { RecoveryKey.format(key) }
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Your recovery key") },
        text = {
            Column {
                Text("Every backup is encrypted with this key. Save it somewhere safe, away from this phone: a password manager, or written down.",
                    color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                Text(
                    text.split("-").chunked(2).joinToString("\n") { it.joinToString("  ") },
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DdfColors.Cyan,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                if (copied) Text("Copied. Paste it where you keep it.", color = DdfColors.Cyan, style = MaterialTheme.typography.bodySmall)
                Text("This is the only time it is shown. Lose it and the backups made with it are gone: nobody can open them, not even the server.",
                    color = DdfColors.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onSaved) { Text("I saved it") } },
        dismissButton = { TextButton(onClick = { onCopy(text); copied = true }) { Text("Copy") } },
    )
}
