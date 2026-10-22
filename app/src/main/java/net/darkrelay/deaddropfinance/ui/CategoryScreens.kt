package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import net.darkrelay.deaddropfinance.data.CategoryEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Money

private val monthFormat = DateTimeFormatter.ofPattern("MMM d")

/** A budget month in words: "This budget month, since Oct 3", "Oct 3 to Oct 16", "Before the first payday". */
fun monthLabel(m: Budget.Month, today: LocalDate): String = when {
    m.start == null && m.end == null -> "All activity (no payday marked yet)"
    m.start == null -> "Before ${m.end!!.format(monthFormat)}"
    m.end == null -> "This budget month, since ${m.start.format(monthFormat)}"
    else -> "${m.start.format(monthFormat)} to ${m.end.minusDays(1).format(monthFormat)}"
}

/**
 * Categories: spending per category for a budget month (payday to payday), biggest first, with the total at the top.
 * Older and Newer step through the budget months; tapping a category lists its purchases. Managing categories is here too.
 */
@Composable
fun CategoriesScreen(
    budget: Budget,
    today: LocalDate,
    monthIndex: Int,
    onMonth: (Int) -> Unit,
    onCategory: (categoryId: String?) -> Unit,
    onManage: () -> Unit,
) {
    val months = budget.months(today)
    val index = monthIndex.coerceIn(0, months.size - 1)
    val month = months[index]
    val totals = budget.categoryTotals(month)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Categories", onBack = null) { TextButton(onClick = onManage) { Text("Manage") } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { onMonth(index + 1) }, enabled = index < months.size - 1) { Text("‹ Older") }
            Text(monthLabel(month, today), Modifier.weight(1f), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onMonth(index - 1) }, enabled = index > 0) { Text("Newer ›") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Total spent", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(Money.format(budget.categoryTotal(month)), style = MaterialTheme.typography.titleLarge, color = DdfColors.Cyan)
        }
        if (totals.isEmpty()) EmptyState("No spending in this budget month.")
        totals.forEach { (c, amount) ->
            ListRow(onClick = { onCategory(c?.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c?.name ?: Budget.UNCATEGORIZED, Modifier.weight(1f), color = if (c == null) DdfColors.Muted else DdfColors.Text)
                    MoneyText(amount)
                }
            }
        }
        if (totals.any { it.first == null }) {
            Text("Uncategorized is spending from before categories. Open a purchase in Activity to give it one.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Gap(24)
    }
}

/** One category's purchases (and returns) for a budget month, newest first. */
@Composable
fun CategoryPurchasesScreen(
    budget: Budget,
    today: LocalDate,
    monthIndex: Int,
    categoryId: String?,
    onBack: () -> Unit,
    onTransaction: (String) -> Unit,
) {
    val months = budget.months(today)
    val month = months[monthIndex.coerceIn(0, months.size - 1)]
    val rows = budget.categoryPurchases(month, categoryId)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(budget.categoryName(categoryId), onBack)
        Text(monthLabel(month, today), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Spent", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(Money.format(rows.sumOf { it.second }), style = MaterialTheme.typography.titleLarge, color = DdfColors.Cyan)
        }
        if (rows.isEmpty()) EmptyState("Nothing in this category for this budget month.")
        rows.forEach { (t, amount) ->
            ListRow(onClick = { onTransaction(t.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.payee.ifBlank { "No description" })
                        Text(t.date.toString() + if (t.returnOf != null) ", a return" else "", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    MoneyText(amount)
                }
            }
        }
        Gap(24)
    }
}

/** Add, rename and delete categories. Deleting one that has purchases asks which category to move them to first. */
@Composable
fun ManageCategoriesScreen(
    categories: List<CategoryEntity>,
    usage: (String) -> Int,
    error: String?,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onDelete: (id: String, moveTo: String?) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<CategoryEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }
    var moveTo by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Manage categories", onBack)
        Text(
            "A category says what a purchase was. Envelopes claim categories in their editor; a category nothing claims goes to Unplanned.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { TextFieldRow("New category", newName, { newName = it }) }
            TextButton(onClick = { onAdd(newName); newName = "" }, enabled = newName.isNotBlank()) { Text("Add") }
        }
        ErrorLine(error)
        categories.forEach { c ->
            ListRow(onClick = null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, Modifier.weight(1f))
                    TextButton(onClick = { renaming = c; renameText = c.name }) { Text("Rename") }
                    TextButton(onClick = { deleting = c; moveTo = null; if (usage(c.id) == 0) onDelete(c.id, null).also { deleting = null } }) { Text("Delete", color = DdfColors.Error) }
                }
            }
        }
        Gap(24)
    }
    renaming?.let { c ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename category") },
            text = { TextFieldRow("Name", renameText, { renameText = it }) },
            confirmButton = { TextButton(onClick = { onRename(c.id, renameText); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { c ->
        val n = usage(c.id)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${c.name}?") },
            text = {
                Column {
                    Text("$n purchase${if (n == 1) "" else "s"} or rule${if (n == 1) "" else "s"} use it. Choose the category to move them to. Their envelopes don't change.")
                    Picker("Move them to", categories.filter { it.id != c.id }.map { it.id to it.name }, moveTo, { moveTo = it })
                }
            },
            confirmButton = { TextButton(onClick = { onDelete(c.id, moveTo); deleting = null }, enabled = moveTo != null) { Text("Delete", color = DdfColors.Error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
