package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.domain.SetupImport
import net.darkrelay.deaddropfinance.domain.SetupImporter

/** The setup import screen's state: the file picked, its plan and preview, or its errors, and the result. */
data class SetupImportState(
    val fileName: String? = null,
    val plan: SetupImport.Plan? = null,
    val preview: SetupImporter.Preview? = null,
    val errors: List<String> = emptyList(),
    val message: String? = null,
    val working: Boolean = false,
)

/**
 * Import setup from a JSON file (docs/IMPORT.md): envelopes, bills, the next payday, rules, subscriptions and
 * settings. It shows what will be added and updated first; Import applies it, Cancel leaves everything as it is.
 */
@Composable
fun SetupImportScreen(s: SetupImportState, onBack: () -> Unit, onPick: () -> Unit, onExportExample: () -> Unit, onImport: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Import setup", onBack)
        Text(
            "Envelopes, bills, the next payday, rules, subscriptions and settings from a JSON file. Never transactions. " +
                "Names that are new are added, names already here are updated, and nothing is deleted.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        if (s.message != null) Text(s.message, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 8.dp))
        val p = s.preview
        when {
            s.errors.isNotEmpty() -> {
                Text("${s.fileName ?: "That file"} can't be imported. Nothing was changed.", color = DdfColors.Error, modifier = Modifier.padding(top = 8.dp))
                s.errors.forEach { Text("- $it", color = DdfColors.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                OutlinedButton(onClick = onPick, modifier = Modifier.padding(top = 8.dp)) { Text("Pick another file") }
            }
            p != null -> {
                Text("From ${s.fileName ?: "the file"}", modifier = Modifier.padding(top = 8.dp))
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Will add ${p.addCount}", style = MaterialTheme.typography.titleMedium)
                        if (p.add.isEmpty()) Text("Nothing new", color = DdfColors.Muted)
                        p.add.forEach { (kind, names) -> PreviewLine(kind, names) }
                        Gap()
                        Text("Will update ${p.updateCount}", style = MaterialTheme.typography.titleMedium)
                        if (p.update.isEmpty()) Text("Nothing already here", color = DdfColors.Muted)
                        p.update.forEach { (kind, names) -> PreviewLine(kind, names) }
                        p.nextPayday?.let { Gap(); PreviewLine("Next payday", listOf(dayText(it))) }
                        if (p.settings.isNotEmpty()) { Gap(); PreviewLine("Settings", p.settings) }
                    }
                }
                Row {
                    Button(onClick = onImport, enabled = !s.working) { Text("Import") }
                    TextButton(onClick = onCancel, enabled = !s.working) { Text("Cancel") }
                }
            }
            else -> Row(Modifier.padding(top = 12.dp)) {
                Button(onClick = onPick) { Text("Pick a JSON file") }
                OutlinedButton(onClick = onExportExample, modifier = Modifier.padding(start = 8.dp)) { Text("Export example") }
            }
        }
        Gap(24)
    }
}

@Composable
private fun PreviewLine(kind: String, names: List<String>) {
    Text("$kind (${names.size})", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Text(names.joinToString(", "), color = DdfColors.Text)
}
