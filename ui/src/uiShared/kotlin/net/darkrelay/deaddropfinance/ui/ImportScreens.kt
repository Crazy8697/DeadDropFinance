package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.testTag
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
    /** 1.9.7: the user's ticks in the preview, by row number (a new row is ticked unless false, an unsure one only when true). */
    val choices: Map<Int, Boolean> = emptyMap(),
    /** 1.9.7: the preview dialog is open. Nothing is written until its Import is pressed. */
    val previewing: Boolean = false,
    val result: BudgetActions.ImportResult? = null,
    val error: String? = null,
)

fun roleName(r: CsvImport.Role) = when (r) {
    CsvImport.Role.DATE -> "Date"
    CsvImport.Role.DESCRIPTION -> "Description"
    CsvImport.Role.ORIGINAL -> "Original description"
    CsvImport.Role.STATUS -> "Status (Pending is skipped)"
    CsvImport.Role.AMOUNT -> "Amount (signed)"
    CsvImport.Role.DEBIT -> "Debit (money out)"
    CsvImport.Role.CREDIT -> "Credit (money in)"
    CsvImport.Role.BALANCE -> "Balance (ignored)"
    CsvImport.Role.MEMO -> "Memo"
    CsvImport.Role.IGNORE -> "Skip this column"
}

/**
 * One screen for the whole Old School import: pick the file, check the column mapping, date format and account, then
 * Preview: a dialog with what's new, already here, unsure and pending, where each row can be ticked or not (1.9.7).
 * Only its Import writes anything. Imported rows land in To sort.
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
    onChoice: (Int, Boolean) -> Unit = { _, _ -> },
    onPreview: (Boolean) -> Unit = {},
    rules: List<net.darkrelay.deaddropfinance.data.RuleEntity> = emptyList(),
) {
    ScrollColumn(Modifier.fillMaxWidth(), rememberScrollState(), Modifier.padding(horizontal = 16.dp)) {
        ScreenTitle("Import CSV", onBack)
        if (budget.accounts.none { !it.archived }) {
            EmptyState("No accounts yet. Add the account this file belongs to first.")
            return@ScrollColumn
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
                    Text("${r.skipped} already here, ${r.skippedByUser} not ticked, ${r.pending} pending not added, ${r.bad} bad rows skipped.", color = DdfColors.Muted)
                }
            }
        }
        val text = s.text ?: return@ScrollColumn
        val layout = s.layout ?: return@ScrollColumn
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
        val account = s.accountId ?: return@ScrollColumn
        val read = CsvImport.read(text, layout)
        val plan = CsvImport.plan(read, account, budget.transactions, s.choices)
        SectionTitle("Preview")
        Text("${read.rows.size} rows read. Preview shows what's new, already here and unsure before anything is saved.", color = DdfColors.Muted)
        if (plan.bad.isNotEmpty()) {
            SectionTitle("Bad rows (skipped)")
            plan.bad.take(30).forEach { b -> Text("Row ${b.rowNumber}: ${b.reason}", color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall) }
        }
        Gap()
        Button(onClick = { onPreview(true) }, enabled = read.rows.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("import.preview")) {
            Text("Preview")
        }
        Gap(24)
        if (s.previewing) {
            val guesses = remember(plan, budget, rules) { net.darkrelay.deaddropfinance.domain.Suggest.guesses(budget, rules, account, plan.new + plan.unsure.map { it.row }) }
            ImportPreviewDialog(plan, guesses, s.choices, onChoice, onCancel = { onPreview(false) }, onImport = { onImport(plan) })
        }
    }
}

/** "4 new, 37 already in, 2 unsure, 1 pending": the preview's first line, the program's too. */
fun importCounts(plan: CsvImport.Plan) = "${plan.new.size} new, ${plan.alreadyCount} already in, ${plan.unsure.size} unsure, ${plan.pending.size} pending"

/** The Import button's words: how many go in, and the pending ones it posts. */
fun importAction(plan: CsvImport.Plan) = "Import ${plan.toImport.size}"

/**
 * The import preview (1.9.7), before anything is saved: the counts, then the new rows (ticked) and the unsure ones (not
 * ticked, with the transaction already here each may be), each with its date, payee, amount and envelope guess; the rows
 * already in under a count that opens; the bank's pending rows under "Pending, not added". The phone's dialog
 * ([FormDialog]); the program draws its own in its dialog frame.
 */
@Composable
fun ImportPreviewDialog(
    plan: CsvImport.Plan, guesses: Map<Int, String?>, choices: Map<Int, Boolean>,
    onChoice: (Int, Boolean) -> Unit, onCancel: () -> Unit, onImport: () -> Unit,
) {
    var showAlready by remember { mutableStateOf(false) }
    val canImport = plan.toImport.isNotEmpty() || plan.matchedPending.isNotEmpty()
    val rows: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            if (plan.matchedPending.isNotEmpty()) Text("${plan.matchedPending.size} of the ones already in post a pending transaction you entered.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            if (plan.new.isNotEmpty()) {
                PreviewHead("New")
                plan.new.forEach { r -> PreviewRow(r, guesses[r.rowNumber], choices[r.rowNumber] != false, { onChoice(r.rowNumber, it) }, "import.new.${r.rowNumber}") }
            }
            if (plan.unsure.isNotEmpty()) {
                PreviewHead("Unsure: tick the ones that are new")
                plan.unsure.forEach { u ->
                    PreviewRow(u.row, guesses[u.row.rowNumber], choices[u.row.rowNumber] == true, { onChoice(u.row.rowNumber, it) }, "import.unsure.${u.row.rowNumber}")
                    Text(
                        "Maybe already here: ${u.maybe.date}, ${u.maybe.payee.ifBlank { "No payee" }}, ${Money.format(u.maybe.amount)}",
                        color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 48.dp, bottom = 4.dp).testTag("import.maybe.${u.row.rowNumber}"),
                    )
                }
            }
            if (plan.alreadyCount > 0) {
                TextButton(onClick = { showAlready = !showAlready }, modifier = Modifier.testTag("import.already")) {
                    Text("${plan.alreadyCount} already in ${if (showAlready) "(hide)" else "(show)"}")
                }
                if (showAlready) (plan.alreadyHere + plan.matchedPending.map { it.first }).sortedBy { it.rowNumber }.forEach { r -> PlainRow(r) }
            }
            if (plan.pending.isNotEmpty()) {
                PreviewHead("Pending, not added")
                plan.pending.forEach { r -> PlainRow(r) }
            }
            if (plan.bad.isNotEmpty()) Text("${plan.bad.size} row${if (plan.bad.size == 1) "" else "s"} can't be read, skipped.", color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (LocalProgramStyle.current || LocalConfirmHost.current != null) {
        // the program's dialog frame, which scrolls when the window is short
        FormDialog(
            "Check the import", onCancel,
            listOf(
                DialogAction("Cancel", onCancel, tag = "import.cancel"),
                DialogAction(importAction(plan), onImport, filled = true, enabled = canImport, tag = "import.go"),
            ),
            tag = "import.dialog", width = 560.dp,
        ) {
            Text(importCounts(plan), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("import.counts"))
            rows()
        }
    } else {
        // the phone: the shared confirm's own look (Cancel quiet on the left, Import filled on the right)
        ConfirmDialog(
            "Check the import", importCounts(plan), importAction(plan), onCancel = onCancel, onConfirm = onImport, enabled = canImport,
            tag = "import.dialog", cancelTag = "import.cancel", actionTag = "import.go", textTag = "import.counts", extra = rows,
        )
    }
}

@Composable
private fun PreviewHead(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = DdfColors.Muted, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
private fun PreviewRow(r: CsvImport.Row, guess: String?, ticked: Boolean, onTick: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = ticked, onCheckedChange = onTick, modifier = Modifier.testTag(tag))
        Column(Modifier.weight(1f)) {
            Text(r.description.ifBlank { "No description" }, maxLines = 1)
            Text("${r.date}, ${guess ?: "no envelope guess"}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        MoneyText(r.amount)
    }
}

@Composable
private fun PlainRow(r: CsvImport.Row) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp)) {
        Text("${r.date}  ${r.description.ifBlank { "No description" }}", Modifier.weight(1f), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Text(Money.format(r.amount), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}


