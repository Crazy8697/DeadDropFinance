package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.domain.Currencies
import net.darkrelay.deaddropfinance.domain.Money

/** "EUR (Euro)": the code and its English name. */
fun currencyLabel(code: String): String = Currencies.all().firstOrNull { it.code == code }?.let { "${it.code} (${it.name})" } ?: code

/**
 * The budget's currency (1.7.0), in More and on setup's first page: what it is, and Change, which opens the picker.
 * Display only: it changes how amounts look and are typed, never an amount, and there are no exchange rates.
 */
@Composable
fun CurrencySetting(code: String, onPick: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Currency")
            Text("${currencyLabel(code)}. How amounts look and are typed, on every device of the account. No amount changes.",
                color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { picking = true }) { Text("Change") }
    }
    if (picking) CurrencyPicker(code, onPick = { picking = false; onPick(it) }, onDismiss = { picking = false })
}

/** The picker: the common currencies first, then every ISO 4217 currency the phone knows, searchable by code or name. */
@Composable
fun CurrencyPicker(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val list = remember(query) { Currencies.pickerList(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Currency") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, label = { Text("Search by code or name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    itemsIndexed(list, key = { _, c -> c.code }) { i, c ->
                        if (query.isBlank() && i == Currencies.commonOffered.size) {
                            Text("All currencies", color = DdfColors.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        Row(Modifier.fillMaxWidth().clickable { onPick(c.code) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.code, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
                            Text(c.name, color = if (c.code == current) DdfColors.Cyan else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text(Money.format(123456, Currencies.style(c.code)), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (list.isEmpty()) item { Text("No currency matches that.", color = DdfColors.Muted) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
