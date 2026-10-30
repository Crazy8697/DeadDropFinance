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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.CsvImport
import net.darkrelay.deaddropfinance.domain.Money

/** What the import screens hold between steps. The file's text lives only here, in memory, and is never saved. */
data class ImportState(
    val fileName: String? = null,
    val text: String? = null,
    val layout: CsvImport.Layout? = null,
    val accountId: String? = null,
    /** Row numbers the user chose to skip in the possible-duplicates section. */
    val skippedRows: Set<Int> = emptySet(),
    val result: BudgetActions.ImportResult? = null,
    val error: String? = null,
)

fun roleName(r: CsvImport.Role) = when (r) {
    CsvImport.Role.DATE -> "Date"
    CsvImport.Role.DESCRIPTION -> "Description"
    CsvImport.Role.AMOUNT -> "Amount (signed)"
    CsvImport.Role.DEBIT -> "Debit (money out)"
    CsvImport.Role.CREDIT -> "Credit (money in)"
    CsvImport.Role.BALANCE -> "Balance (ignored)"
    CsvImport.Role.MEMO -> "Memo"
    CsvImport.Role.IGNORE -> "Skip this column"
}

/**
 * One screen for the whole Old School import: pick the file, check the column mapping, date format and account,
 * see the preview with counts, then import. Imported rows land in To sort.
 */
@Composable
fun ImportScreen(
    budget: Budget,
    s: ImportState,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onLayout: (CsvImport.Layout) -> Unit,
    onAccount: (String) -> Unit,
    onImport: (CsvImport.Plan) -> Unit,
    onToggleSkip: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Import CSV", onBack)
        if (budget.accounts.none { !it.archived }) {
            EmptyState("No accounts yet. Add the account this file belongs to first.")
            return@Column
        }
        Text("Choose a CSV file exported from your bank or payment app. It's read once and never stored.", color = DdfColors.Muted)
        Gap()
        Button(onClick = onPick) { Text(if (s.text == null) "Choose a file" else "Choose another file") }
        ErrorLine(s.error)
        s.result?.let { r ->
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Imported ${r.imported} into To sort.")
                    if (r.posted > 0) Text("${r.posted} pending ${if (r.posted == 1) "transaction was" else "transactions were"} posted.")
                    Text("${r.skipped} already here (skipped), ${r.skippedByUser} skipped by you, ${r.bad} bad rows skipped.", color = DdfColors.Muted)
                }
            }
        }
        val text = s.text ?: return@Column
        val layout = s.layout ?: return@Column
        s.fileName?.let { Text(it, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
        Picker("Into account", budget.accounts.filter { !it.archived }.map { it.id to it.name }, s.accountId, onAccount)
        SectionTitle("Columns")
        layout.headers.forEachIndexed { i, h ->
            Picker(h.ifBlank { "Column ${i + 1}" }, CsvImport.Role.entries.map { it to roleName(it) }, layout.roles[i], { r ->
                onLayout(layout.copy(roles = layout.roles.toMutableList().also { it[i] = r }))
            })
        }
        Picker("Date format", CsvImport.DATE_FORMATS.map { it to it }, layout.dateFormat, { onLayout(layout.copy(dateFormat = it, dateAmbiguous = false)) })
        if (layout.dateAmbiguous) Text("These dates read both month-first and day-first. Check the format.", color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall)
        val account = s.accountId ?: return@Column
        val read = CsvImport.read(text, layout)
        val plan = CsvImport.plan(read, account, budget.transactions, s.skippedRows)
        SectionTitle("Preview")
        Text(
            "${plan.total} rows: ${plan.toImport.size} to import, ${plan.alreadyHere.size} already here (skipped), " +
                "${plan.matchedPending.size} posting a pending one, ${plan.possibleDuplicateRows} possible duplicates, ${plan.skippedByUser.size} skipped by you, ${plan.bad.size} bad",
            color = DdfColors.Muted,
        )
        read.rows.take(8).forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.weight(1f)) { Text(r.description.ifBlank { "No description" }); Text(r.date.toString(), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
                MoneyText(r.amount)
            }
        }
        if (plan.possibleDuplicates.isNotEmpty()) {
            SectionTitle("Possible duplicates in this file")
            Text(
                "These rows are identical. Two real purchases can look the same, so they are imported unless you skip them.",
                color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
            plan.possibleDuplicates.forEach { g ->
                val first = g.rows.first()
                Gap(8)
                Text("${first.description.ifBlank { "No description" }}, ${first.date}, ${Money.format(first.amount)}", style = MaterialTheme.typography.bodyMedium)
                g.rows.forEach { r ->
                    val skip = r.rowNumber in s.skippedRows
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Row ${r.rowNumber}", Modifier.weight(1f), color = DdfColors.Muted)
                        Text(if (skip) "Skip" else "Import", color = if (skip) DdfColors.Warning else DdfColors.Cyan)
                        HGap(12)
                        Switch(checked = !skip, onCheckedChange = { onToggleSkip(r.rowNumber) })
                    }
                }
            }
        }
        if (plan.bad.isNotEmpty()) {
            SectionTitle("Bad rows (skipped)")
            plan.bad.take(30).forEach { b -> Text("Row ${b.rowNumber}: ${b.reason}", color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall) }
        }
        Gap()
        Button(onClick = { onImport(plan) }, enabled = plan.toImport.isNotEmpty() || plan.matchedPending.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Import ${plan.toImport.size} into To sort")
        }
        Gap(24)
    }
}

