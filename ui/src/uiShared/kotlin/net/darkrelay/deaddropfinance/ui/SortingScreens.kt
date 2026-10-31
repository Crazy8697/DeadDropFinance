package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.data.RuleAction
import net.darkrelay.deaddropfinance.data.RuleEntity
import net.darkrelay.deaddropfinance.data.RuleOrigin
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.Payees
import net.darkrelay.deaddropfinance.domain.Rules
import net.darkrelay.deaddropfinance.domain.Sorting

// ---- Rules ------------------------------------------------------------------------------------------------------

/** More, Rules: every rule in the order they're tried, with Up and Down to change it. */
@Composable
fun RulesScreen(rules: List<RuleEntity>, budget: Budget, onBack: () -> Unit, onRule: (String) -> Unit, onAdd: () -> Unit, onMove: (String, Boolean) -> Unit) {
    val envName = { id: String -> budget.envelopes.firstOrNull { it.id == id }?.name }
    val acctName = { id: String -> budget.accounts.firstOrNull { it.id == id }?.name }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Rules", onBack) { TextButton(onClick = onAdd) { Text("Add") } }
        Text(
            "Rules suggest where new transactions go, by payee. They're tried top to bottom and the first match wins. A rule never changes " +
                "anything already sorted, history before an account's starting balance, or accounts outside the budget.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        if (rules.isEmpty()) EmptyState("No rules yet. Sort something by hand and the ${LocalDeviceWords.current.product} offers to make one, or add one here.")
        rules.forEachIndexed { i, r ->
            ListRow(onClick = { onRule(r.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row { Text("\"${r.matchText}\""); if (r.origin == RuleOrigin.LEARNED) Pill("learned") }
                        Text(Rules.describe(r, envName) { id -> budget.category(id)?.name }, color = DdfColors.Cyan, style = MaterialTheme.typography.bodySmall)
                        Text(Rules.conditions(r, acctName), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onMove(r.id, true) }, enabled = i > 0) { Text("Up") }
                    TextButton(onClick = { onMove(r.id, false) }, enabled = i < rules.lastIndex) { Text("Down") }
                }
            }
        }
        Gap(24)
    }
}

/** A starting point for a new rule (from a transaction: its cleaned payee and where it went). */
data class RulePrefill(val matchText: String, val direction: Int, val accountId: String?, val target: String?)

/**
 * Add or edit a rule, with a preview of what it matches before it's saved: what's in To sort it would suggest for,
 * and how many earlier transactions it matches (those are never changed).
 */
@Composable
fun RuleEditScreen(
    existing: RuleEntity?, prefill: RulePrefill?, budget: Budget, rules: List<RuleEntity>, error: String?,
    onBack: () -> Unit, onSave: (Sorting.RuleDraft) -> Unit, onDelete: (() -> Unit)?,
) {
    var billId by remember { mutableStateOf(existing?.billId) }
    var categoryId by remember { mutableStateOf(existing?.categoryId) }
    val firstSplits = existing?.let { Rules.splits(it) } ?: listOfNotNull(prefill?.target?.takeIf { it != Sorting.TRANSFER }?.let { Rules.Split(it, 1) })
    var text by remember { mutableStateOf(existing?.matchText ?: prefill?.matchText ?: "") }
    var direction by remember { mutableIntStateOf(existing?.direction ?: prefill?.direction ?: -1) }
    var account by remember { mutableStateOf(existing?.accountId) }
    var min by remember { mutableStateOf(existing?.minAmount?.let { Money.plain(it) } ?: "") }
    var max by remember { mutableStateOf(existing?.maxAmount?.let { Money.plain(it) } ?: "") }
    var does by remember {
        mutableStateOf(
            when {
                existing?.action == RuleAction.TRANSFER || prefill?.target == Sorting.TRANSFER -> "transfer"
                existing?.categoryId != null && firstSplits.isEmpty() -> "category"
                firstSplits.size > 1 -> "split"
                else -> "envelope"
            },
        )
    }
    var single by remember { mutableStateOf(firstSplits.singleOrNull()?.target) }
    data class Line(val target: String?, val amount: String)
    val lines = remember {
        mutableStateListOf<Line>().apply {
            if (firstSplits.size > 1) firstSplits.forEach { add(Line(it.target, Money.plain(it.weight))) } else { add(Line(single, "")); add(Line(null, "")) }
        }
    }
    var alwaysAsk by remember { mutableStateOf(existing?.alwaysAsk ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun amt(s: String) = if (s.isBlank()) null else Money.parse(s)?.let { kotlin.math.abs(it) }
    val targets: List<Pair<String?, String>> =
        (if (direction > 0) listOf<Pair<String?, String>>(Rules.RTA to "Ready to assign") else emptyList()) + budget.fillOrder.map { it.id to it.name }
    val draft = Sorting.RuleDraft(
        matchText = text, accountId = account, direction = direction, minAmount = amt(min), maxAmount = amt(max),
        action = if (does == "transfer") RuleAction.TRANSFER else RuleAction.ENVELOPE,
        splits = when (does) {
            "envelope" -> listOfNotNull(single?.let { Rules.Split(it, 1) })
            "split" -> lines.mapNotNull { l -> l.target?.let { Rules.Split(it, amt(l.amount) ?: 0) } }
            else -> emptyList()
        },
        alwaysAsk = alwaysAsk,
        billId = billId,
        categoryId = categoryId.takeIf { does != "transfer" },
    )
    val categoryChoices: List<Pair<String?, String>> = budget.categories.map { it.id to it.name }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(if (existing == null) "New rule" else "Edit rule", onBack)
        TextFieldRow("Payee has", text, { text = it })
        val clean = Payees.words(text)
        Text(
            if (clean.isEmpty()) "Type part of the payee's name, as the bank shows it." else "Matches payees with \"$clean\" in them, after card numbers, dates and codes are cleaned off.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        Picker("Money", listOf(-1 to "Money out", 1 to "Money in", 0 to "In or out"), direction, { direction = it })
        Row {
            Box(Modifier.weight(1f)) { AmountField("From (optional)", min, { min = it }) }
            HGap()
            Box(Modifier.weight(1f)) { AmountField("To (optional)", max, { max = it }) }
        }
        Picker("Account", listOf<Pair<String?, String>>(null to "Any account") + budget.accounts.filter { it.onBudget }.map { it.id to it.name }, account, { account = it })
        Picker(
            "Does",
            listOf(
                "category" to "Sorts it by a category",
                "envelope" to "Puts it in an envelope",
                "split" to "Splits it across envelopes",
                "transfer" to "Marks it a transfer between my accounts",
            ),
            does, { does = it },
        )
        when (does) {
            "category" -> {
                Picker("Category", categoryChoices, categoryId, { categoryId = it })
                Text("It goes to the envelope that claims the category, or to Unplanned if none does.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            "envelope" -> Picker("Envelope", targets, single, { single = it })
            "split" -> {
                Text("A different amount is split in the same proportions.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                lines.forEachIndexed { i, l ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Picker("Envelope", targets, l.target, { lines[i] = l.copy(target = it) })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { AmountField("Amount", l.amount, { lines[i] = l.copy(amount = it) }) }
                                if (lines.size > 2) TextButton(onClick = { lines.removeAt(i) }) { Text("Remove") }
                            }
                        }
                    }
                }
                TextButton(onClick = { lines.add(Line(null, "")) }) { Text("Add a line") }
            }
            else -> Text(
                "Not spending or income: it comes out of or goes into Ready to Assign, and the other account's side puts it back.",
                color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        if ((does == "envelope" || does == "split") && direction < 0) {
            Picker("Category (optional)", listOf<Pair<String?, String>>(null to "No category") + categoryChoices, categoryId, { categoryId = it })
        }
        SettingRow("Always ask", "Suggests, but never sorts by itself, even with auto-approve on. For stores that sell everything.", alwaysAsk, true, { alwaysAsk = it })
        if (budget.bills.isNotEmpty()) {
            Picker("Counts toward bill", listOf<Pair<String?, String>>(null to "No bill") + budget.bills.filter { !it.deleted }.map { it.id to it.name }, billId, { billId = it })
            Text("A match counts as a payment toward the bill, whatever the amount (unless the rule has an amount range).", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }

        // preview, before saving
        SectionTitle("What it matches")
        val probe = RuleEntity(
            id = existing?.id ?: "preview", matchText = clean, accountId = account, direction = direction, minAmount = draft.minAmount, maxAmount = draft.maxAmount,
            action = draft.action, splits = Rules.format(draft.splits), categoryId = draft.categoryId, createdAt = 0, updatedAt = 0,
        )
        val inToSort = budget.toSort.filter { Rules.matches(probe, it) }
        // a new rule goes first; an existing one can be beaten by the rules above it
        val earlierRules = if (existing == null) emptyList() else rules.filter { it.id != existing.id && it.priority < existing.priority }
        val caught = inToSort.count { t -> earlierRules.any { Rules.matches(it, t) } }
        val toSortIds = budget.toSort.map { it.id }.toSet()
        val past = budget.transactions.count { t -> t.id !in toSortIds && budget.isOnBudget(t.accountId) && Rules.matches(probe, t) }
        if (clean.isEmpty()) {
            Text("Nothing yet.", color = DdfColors.Muted)
        } else {
            Text(
                (if (inToSort.isEmpty()) "Nothing in To sort right now." else "${inToSort.size} in To sort would get this suggestion" + (if (caught > 0) " ($caught caught first by a rule above it)" else "") + ":") +
                    if (past > 0) " It also matches $past earlier transaction${if (past == 1) "" else "s"}, which stay as they are." else "",
                color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
            inToSort.take(5).forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text("${t.payee.ifBlank { "No description" }}, ${t.date}", Modifier.weight(1f)); MoneyText(t.amount) }
            }
            if (inToSort.size > 5) Text("and ${inToSort.size - 5} more", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        ErrorLine(error)
        Gap()
        Button(onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        if (onDelete != null) {
            Gap()
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete rule", color = DdfColors.Error) }
        }
        Gap(24)
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this rule?") },
            text = { Text("New transactions from this payee won't get its suggestion any more. Nothing already sorted changes.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = DdfColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---- More: sorting and alerts -----------------------------------------------------------------------------------

data class SortAlertState(
    val ruleCount: Int,
    val autoApprove: Boolean,
    val amountsOnLockScreen: Boolean,
    val notificationsAllowed: Boolean,
    val bills: Boolean = true,
    val autopay: Boolean = true,
)

/** More's Sorting and Alerts sections. */
@Composable
fun SortingAndAlertsSection(
    s: SortAlertState, onRules: () -> Unit, onAutoApprove: (Boolean) -> Unit, onAlert: (key: String, on: Boolean) -> Unit, onAllowNotifications: () -> Unit,
) {
    SectionTitle("Sorting")
    ListRow(onRules) {
        Column {
            Text("Rules")
            Text(
                if (s.ruleCount == 0) "Suggest envelopes for new transactions" else "${s.ruleCount} rule${if (s.ruleCount == 1) "" else "s"}, suggesting envelopes by payee",
                color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    SettingRow(
        "Approve rule matches automatically",
        "Off: rule matches wait in To sort for one ${LocalDeviceWords.current.tap.lowercase()}. On: they're sorted as they arrive and listed under Recently sorted. Rules set to Always ask still wait.",
        s.autoApprove, true, onAutoApprove,
    )
    SectionTitle("Alerts")
    if (!s.notificationsAllowed) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications are off for this ${LocalDeviceWords.current.product}.", Modifier.weight(1f), color = DdfColors.Warning)
            TextButton(onClick = onAllowNotifications) { Text("Turn on") }
        }
    }
    SettingRow("Bill reminders", "For the bills (and debt payments) you turn a reminder on for. Each bill's reminder is off until you do.", s.bills, true) { onAlert(ALERT_BILLS, it) }
    SettingRow("Autopay warnings", "When an autopay is due before payday and its envelope is short.", s.autopay, true) { onAlert(ALERT_AUTOPAY, it) }
    // the phone's lock screen; Windows notifications have no such setting here
    if (LocalDeviceWords.current.appLock) SettingRow("Show amounts on the lock screen", "Off: the lock screen only says a bill is due or an autopay is short, with no names or amounts.", s.amountsOnLockScreen, true) { onAlert(ALERT_LOCK_AMOUNTS, it) }
}

const val ALERT_LOCK_AMOUNTS = "lock_amounts"
const val ALERT_BILLS = "bills"
const val ALERT_AUTOPAY = "autopay"
