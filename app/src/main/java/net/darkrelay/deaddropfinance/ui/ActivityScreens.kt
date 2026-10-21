package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.Rules
import net.darkrelay.deaddropfinance.domain.Sorting
import net.darkrelay.deaddropfinance.domain.Suggest
import net.darkrelay.deaddropfinance.data.RuleEntity
import net.darkrelay.deaddropfinance.data.SortedBy
import androidx.compose.material3.AlertDialog

/** What the To sort list needs for the approve flow; the defaults show a plain list. */
data class SortingUi(
    val suggestions: Map<String, Suggest.Suggestion> = emptyMap(),
    val onApprove: (String) -> Unit = {},
    val onApproveAll: () -> Unit = {},
    /** Sort by hand as a transfer between the user's own accounts. */
    val onTransfer: (String) -> Unit = {},
    /** The "Always put this payee there?" offer after a choice by hand. */
    val offer: Sorting.Learned.Offer? = null,
    val onOffer: (accept: Boolean) -> Unit = {},
    /** Rules learned from repeated choices whose notice hasn't been seen. */
    val learned: List<RuleEntity> = emptyList(),
    val onLearned: (ruleId: String, undo: Boolean) -> Unit = { _, _ -> },
    /** Sorted automatically by a rule lately (auto-approve), newest first. */
    val recentlySorted: List<TransactionEntity> = emptyList(),
    val rules: List<RuleEntity> = emptyList(),
)

/**
 * Activity: To sort with each transaction's suggestion (one tap approves it; picking an envelope changes it; Split
 * opens the split), Approve all suggested (with the count first), what rules sorted lately, and recent transactions.
 */
@Composable
fun ActivityScreen(
    budget: Budget,
    onAdd: () -> Unit,
    onTransaction: (String) -> Unit,
    onSort: (String, String?, Boolean) -> Unit,
    onSplit: (String) -> Unit,
    syncNotes: List<String> = emptyList(),
    sorting: SortingUi = SortingUi(),
) {
    val accounts = budget.accounts.associateBy { it.id }
    val envelopes = budget.envelopes.associateBy { it.id }
    var confirmAll by remember { mutableStateOf(false) }
    val suggested = budget.toSort.count { it.id in sorting.suggestions }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Activity", onBack = null) { TextButton(onClick = onAdd, enabled = budget.accounts.any { !it.archived }) { Text("Add") } }
        if (budget.accounts.none { !it.archived }) EmptyState("No accounts yet. Add one in More to get started.")
        if (syncNotes.isNotEmpty()) {
            SectionTitle("From bank sync")
            syncNotes.forEach { Text(it, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        sorting.learned.forEach { r -> LearnedRuleCard(r, budget) { undo -> sorting.onLearned(r.id, undo) } }
        sorting.offer?.let { o -> OfferCard(o, budget, sorting.onOffer) }
        SectionTitle("To sort") {
            if (suggested > 0) TextButton(onClick = { confirmAll = true }) { Text("Approve all suggested ($suggested)") }
        }
        if (budget.toSort.isEmpty()) EmptyState("Nothing to sort.")
        budget.toSort.forEach { t ->
            ToSortRow(t, budget, accounts[t.accountId]?.name ?: "", sorting.suggestions[t.id], envelopes, onTransaction, onSort, onSplit, sorting)
        }
        if (sorting.recentlySorted.isNotEmpty()) {
            SectionTitle("Recently sorted by rules")
            Text("Auto-approve sorted these as they arrived. Tap one to change it.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            sorting.recentlySorted.forEach { t -> TransactionRow(t, budget, onClick = { onTransaction(t.id) }) }
        }
        SectionTitle("Recent")
        val skip = budget.toSort.map { it.id }.toSet() + sorting.recentlySorted.map { it.id }
        val recent = budget.transactions.filter { it.id !in skip }.take(100)
        if (recent.isEmpty()) EmptyState("No transactions yet. Add one, or import a CSV from your bank in More.")
        recent.forEach { t -> TransactionRow(t, budget, onClick = { onTransaction(t.id) }) }
        Gap(24)
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Approve $suggested suggested?") },
            text = {
                Text(
                    "$suggested of the ${budget.toSort.size} in To sort have a suggestion. Each goes where its suggestion says, splits and returns " +
                        "included. You can change any of them afterwards.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmAll = false; sorting.onApproveAll() }) { Text("Approve $suggested") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

/** One To sort transaction: its suggestion with Approve, or the plain picker; Split always there. */
@Composable
private fun ToSortRow(
    t: TransactionEntity, budget: Budget, accountName: String, s: Suggest.Suggestion?, envelopes: Map<String, net.darkrelay.deaddropfinance.data.EnvelopeEntity>,
    onTransaction: (String) -> Unit, onSort: (String, String?, Boolean) -> Unit, onSplit: (String) -> Unit, sorting: SortingUi,
) {
    ListRow(onClick = { onTransaction(t.id) }) {
        Row {
            Column(Modifier.weight(1f)) {
                Row { Text(t.payee.ifBlank { "No description" }); if (t.pending) Pill("pending") }
                Text("${t.date}, $accountName", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            MoneyText(t.amount)
        }
        if (s != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(suggestionText(s, envelopes), color = DdfColors.Cyan)
                    Text(basisText(s), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { sorting.onApprove(t.id) }) { Text(if (s.basis == Suggest.Basis.RETURN) "Confirm" else "Approve") }
            }
        }
        val options: List<Pair<String?, String>> =
            (if (t.amount > 0) listOf<Pair<String?, String>>(null to "Ready to assign") else emptyList()) +
                budget.fillOrder.map { it.id to it.name } + listOf<Pair<String?, String>>(TRANSFER_PICK to "Transfer between my accounts")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Picker(if (s == null) "Put it in" else "Or put it in", options, selected = "unset-${t.id}", onSelect = { id ->
                    if (id == TRANSFER_PICK) sorting.onTransfer(t.id) else onSort(t.id, id, id == null)
                })
            }
            TextButton(onClick = { onSplit(t.id) }) { Text("Split") }
        }
    }
}

/** The picker value for "Transfer between my accounts" (never an envelope id). */
private const val TRANSFER_PICK = "\u0000transfer"

fun suggestionText(s: Suggest.Suggestion, envelopes: Map<String, net.darkrelay.deaddropfinance.data.EnvelopeEntity>): String {
    fun name(l: BudgetActions.LineDraft) = when {
        l.transfer -> "Transfer between your accounts"
        l.toReadyToAssign -> "Ready to assign"
        else -> envelopes[l.envelopeId]?.name ?: "an envelope"
    }
    val parts = s.lines.joinToString(", ") { "${name(it)} ${Money.format(it.amount)}" }
    val where = if (s.lines.size == 1) name(s.lines[0]) else if (s.basis == Suggest.Basis.RETURN) parts else "Split: $parts"
    return when (s.basis) {
        Suggest.Basis.RETURN -> "Return of ${s.returnOf?.payee?.ifBlank { null } ?: "a purchase"} on ${s.returnOf?.date}: back to $where"
        else -> where
    }
}

fun basisText(s: Suggest.Suggestion): String = when (s.basis) {
    Suggest.Basis.RULE -> "Rule: payee has \"${s.rule?.matchText}\"" + if (s.rule?.alwaysAsk == true) ", always asks" else ""
    Suggest.Basis.HISTORY -> "Where you put this payee last time"
    Suggest.Basis.RETURN -> "Money back from a store you bought from lately"
}

@Composable
private fun OfferCard(o: Sorting.Learned.Offer, budget: Budget, onAnswer: (Boolean) -> Unit) {
    val where = targetName(o.target, budget)
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Always put \"${o.key}\" in $where?")
            Text("A rule would suggest it for every new one. Rules are in More.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { onAnswer(true) }) { Text("Make a rule") }
                TextButton(onClick = { onAnswer(false) }) { Text("No thanks") }
            }
        }
    }
}

@Composable
private fun LearnedRuleCard(r: RuleEntity, budget: Budget, onAnswer: (undo: Boolean) -> Unit) {
    val where = Rules.describe(r) { id -> budget.envelopes.firstOrNull { it.id == id }?.name }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("New rule: \"${r.matchText}\" goes to $where")
            Text("Made from your last ${Sorting.LEARN_AFTER} choices for this payee.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { onAnswer(true) }) { Text("Undo") }
                TextButton(onClick = { onAnswer(false) }) { Text("OK") }
            }
        }
    }
}

private fun targetName(target: String, budget: Budget) = when (target) {
    Rules.RTA -> "Ready to assign"
    Sorting.TRANSFER -> "transfers between your accounts"
    else -> budget.envelopes.firstOrNull { it.id == target }?.name ?: "that envelope"
}

@Composable
fun TransactionRow(t: TransactionEntity, budget: Budget, onClick: () -> Unit) {
    val accounts = budget.accounts.associateBy { it.id }
    val envelopes = budget.envelopes.associateBy { it.id }
    val sub = when {
        t.kind == TransactionKind.TRANSFER -> "Transfer to ${accounts[t.transferAccountId]?.name ?: "?"}"
        t.isWash -> "Wash (pass-through)"
        budget.beforeStart(t) -> "Before the starting balance"
        else -> {
            val ls = budget.lines.filter { it.transactionId == t.id }
            if (ls.size > 1) "Split, ${ls.size} parts" else when (ls.firstOrNull()?.bucket) {
                LineBucket.ENVELOPE -> envelopes[ls.first().envelopeId]?.name ?: ""
                LineBucket.READY_TO_ASSIGN -> "Ready to assign"
                LineBucket.TRANSFER -> "Transfer between your accounts"
                else -> "To sort"
            }.let { if (t.returnOf != null) "Return, back to $it" else if (t.sortedBy == SortedBy.AUTO) "$it, by a rule" else it }
        }
    }
    ListRow(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row {
                    Text(t.payee.ifBlank { if (t.kind == TransactionKind.TRANSFER) "Transfer" else "No description" })
                    if (t.isPayday) Pill("payday")
                    if (t.pending) Pill("pending")
                    if (t.fee) Pill("fee")
                    if (t.feeRebate) Pill("fee rebate")
                }
                Text("${t.date}, $sub", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            MoneyText(if (t.kind == TransactionKind.TRANSFER) -t.amount else t.amount, color = if (t.amount > 0 && t.kind == TransactionKind.NORMAL) DdfColors.Cyan else null)
        }
    }
}

enum class EditKind { SPEND, INCOME, TRANSFER }

/**
 * Add or edit a transaction: spending, income or a transfer. Spending and income go to one envelope or a split
 * across several (the lines must add up before it saves). Income can go to Ready to Assign or straight into an
 * envelope. A transfer into a cash account can take the cash from an envelope (an ATM withdrawal).
 */
@Composable
fun TransactionEditScreen(
    budget: Budget,
    existing: TransactionEntity?,
    startSplit: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSave: (BudgetActions.TransactionDraft) -> Unit,
    onSaveTransfer: (from: String, to: String, amount: Long, date: LocalDate, memo: String, fromEnvelope: String?) -> Unit,
) {
    val accounts = budget.accounts.filter { !it.archived || it.id == existing?.accountId }
    val existingLines = existing?.let { e -> budget.lines.filter { it.transactionId == e.id }.sortedBy { it.sortOrder } } ?: emptyList()
    var kind by remember {
        mutableStateOf(
            when {
                existing?.kind == TransactionKind.TRANSFER -> EditKind.TRANSFER
                existing != null && existing.amount > 0 -> EditKind.INCOME
                else -> EditKind.SPEND
            },
        )
    }
    var account by remember { mutableStateOf(existing?.accountId ?: accounts.firstOrNull()?.id) }
    var toAccount by remember { mutableStateOf(existing?.transferAccountId ?: accounts.getOrNull(1)?.id) }
    var date by remember { mutableStateOf((existing?.date ?: LocalDate.now()).toString()) }
    var payee by remember { mutableStateOf(existing?.payee ?: "") }
    var memo by remember { mutableStateOf(existing?.memo ?: "") }
    var amount by remember { mutableStateOf(existing?.amount?.let { Money.plain(kotlin.math.abs(it)) } ?: "") }
    var pending by remember { mutableStateOf(existing?.pending ?: false) }
    var split by remember { mutableStateOf(startSplit || existingLines.size > 1) }
    // single destination: an envelope id, "rta" for Ready to Assign, or null for To sort
    var single by remember {
        mutableStateOf(
            existingLines.singleOrNull()?.let { if (it.bucket == LineBucket.READY_TO_ASSIGN) "rta" else it.envelopeId }
                ?: if (existing == null && kind == EditKind.INCOME) "rta" else null,
        )
    }
    data class SplitLine(val envelope: String?, val amount: String)
    val splitLines = remember {
        mutableStateListOf<SplitLine>().apply {
            if (existingLines.size > 1) existingLines.forEach { add(SplitLine(if (it.bucket == LineBucket.READY_TO_ASSIGN) "rta" else it.envelopeId, Money.plain(kotlin.math.abs(it.amount)))) }
            else { add(SplitLine(single, "")); add(SplitLine(null, "")) }
        }
    }
    var atmEnvelope by remember { mutableStateOf<String?>(null) }
    val envelopeOptions: List<Pair<String?, String>> = budget.fillOrder.map { it.id to it.name }
    val destOptions: List<Pair<String?, String>> =
        (if (kind == EditKind.INCOME) listOf<Pair<String?, String>>("rta" to "Ready to assign") else emptyList()) + envelopeOptions +
            listOf<Pair<String?, String>>(null to "Not sorted yet (To sort)")
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(if (existing == null) "New transaction" else "Edit transaction", onBack)
        if (existing == null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                EditKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(selected = kind == k, onClick = { kind = k; if (k == EditKind.INCOME && single == null) single = "rta" }, shape = SegmentedButtonDefaults.itemShape(i, EditKind.entries.size)) {
                        Text(when (k) { EditKind.SPEND -> "Spending"; EditKind.INCOME -> "Income"; EditKind.TRANSFER -> "Transfer" })
                    }
                }
            }
        }
        Picker(if (kind == EditKind.TRANSFER) "From account" else "Account", accounts.map { it.id to it.name }, account, { account = it })
        if (kind == EditKind.TRANSFER) {
            Picker("To account", accounts.map { it.id to it.name }, toAccount, { toAccount = it })
            if (accounts.firstOrNull { it.id == toAccount }?.type == AccountType.CASH && existing == null) {
                Picker("Take the cash from envelope (ATM withdrawal)", listOf<Pair<String?, String>>(null to "No envelope") + envelopeOptions, atmEnvelope, { atmEnvelope = it })
            }
        }
        DateField("Date", date, { date = it })
        if (kind != EditKind.TRANSFER) TextFieldRow(if (kind == EditKind.INCOME) "From" else "Payee or description", payee, { payee = it })
        AmountField("Amount", amount, { amount = it })
        TextFieldRow("Memo", memo, { memo = it })
        if (kind != EditKind.TRANSFER) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(pending, { pending = it }); Text("Pending (the bank hasn't posted it yet)") }
            if (!split) {
                Picker("Goes to", destOptions, single, { single = it })
                TextButton(onClick = { split = true; splitLines[0] = splitLines[0].copy(envelope = single) }) { Text("Split across envelopes") }
            } else {
                SectionTitle("Split")
                val total = Money.parse(amount)?.let { kotlin.math.abs(it) } ?: 0
                val used = splitLines.sumOf { Money.parse(it.amount)?.let { a -> kotlin.math.abs(a) } ?: 0 }
                Text("Left to split: ${Money.format(total - used)}", color = if (total == used) DdfColors.Cyan else DdfColors.Warning)
                splitLines.forEachIndexed { i, l ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Picker("Envelope", destOptions, l.envelope, { splitLines[i] = l.copy(envelope = it) })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { AmountField("Amount", l.amount, { splitLines[i] = l.copy(amount = it) }) }
                                if (splitLines.size > 2) TextButton(onClick = { splitLines.removeAt(i) }) { Text("Remove") }
                            }
                        }
                    }
                }
                Row {
                    TextButton(onClick = { splitLines.add(SplitLine(null, "")) }) { Text("Add a line") }
                    TextButton(onClick = { split = false }) { Text("Don't split") }
                }
            }
        }
        ErrorLine(error)
        Gap()
        val sign = if (kind == EditKind.SPEND) -1 else 1
        Button(
            onClick = {
                val cents = Money.parse(amount)?.let { kotlin.math.abs(it) } ?: 0
                val d = parseDateOrNull(date) ?: LocalDate.now()
                if (kind == EditKind.TRANSFER) {
                    onSaveTransfer(account ?: "", toAccount ?: "", cents, d, memo, atmEnvelope)
                } else {
                    fun line(dest: String?, a: Long) = BudgetActions.LineDraft(dest?.takeIf { it != "rta" }, a, toReadyToAssign = dest == "rta")
                    val lines = if (!split) listOf(line(single, sign * cents)) else splitLines.map { line(it.envelope, sign * (Money.parse(it.amount)?.let { a -> kotlin.math.abs(a) } ?: 0)) }
                    onSave(BudgetActions.TransactionDraft(account ?: "", d, payee, memo, sign * cents, lines, pending))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        Gap(24)
    }
}

/** A transaction's own screen: where its money went, and what can be done with it. */
@Composable
fun TransactionDetailScreen(
    t: TransactionEntity,
    budget: Budget,
    washCandidates: List<TransactionEntity>,
    message: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPayday: (Boolean) -> Unit,
    onWash: (String) -> Unit,
    onUnwash: () -> Unit,
    /** Purchases this credit could be the return of, most likely first. */
    returnCandidates: List<TransactionEntity> = emptyList(),
    onReturn: (purchaseId: String) -> Unit = {},
    onNotReturn: () -> Unit = {},
    onMakeRule: (() -> Unit)? = null,
    /** Start with the return picker open (screenshots). */
    pickingReturn: Boolean = false,
) {
    val accounts = budget.accounts.associateBy { it.id }
    val envelopes = budget.envelopes.associateBy { it.id }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickReturn by remember { mutableStateOf(pickingReturn) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(t.payee.ifBlank { if (t.kind == TransactionKind.TRANSFER) "Transfer" else "Transaction" }, onBack) { TextButton(onClick = onEdit) { Text("Edit") } }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                MoneyText(t.amount, bold = true)
                Text("${t.date}, ${accounts[t.accountId]?.name ?: ""}" + (if (t.kind == TransactionKind.TRANSFER) " to ${accounts[t.transferAccountId]?.name ?: ""}" else ""), color = DdfColors.Muted)
                if (t.memo.isNotBlank()) Text(t.memo)
                if (t.pending) Text("Pending: when the bank's version is imported, it takes this one's place.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (budget.beforeStart(t)) {
                    Text(
                        "This is dated before the account's starting balance date, so it's already part of that balance. It's kept as history " +
                            "and doesn't change the balance or the budget.",
                        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (t.fee) Text("Tagged as an ATM fee.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (t.feeRebate) Text("Tagged as a fee rebate.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (t.kind == TransactionKind.NORMAL) {
            SectionTitle("Where it went")
            budget.lines.filter { it.transactionId == t.id }.sortedBy { it.sortOrder }.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(if (budget.beforeStart(t)) "History, not counted" else when (l.bucket) { LineBucket.ENVELOPE -> envelopes[l.envelopeId]?.name ?: "?"; LineBucket.READY_TO_ASSIGN -> "Ready to assign"; LineBucket.UNSORTED -> "To sort"; LineBucket.OFF_BUDGET -> "Outside the budget (track only)"; LineBucket.TRANSFER -> "Transfer between your accounts" }, Modifier.weight(1f))
                    MoneyText(l.amount)
                }
            }
            if (t.sortedBy == SortedBy.AUTO) Text("Sorted by a rule as it arrived (auto-approve). Edit to change it.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            if (budget.lines.any { it.transactionId == t.id && it.bucket == LineBucket.TRANSFER }) {
                Text(
                    "A transfer between your own accounts: not spending or income. It comes out of (or goes into) Ready to Assign, and the other " +
                        "account's side puts it back.",
                    color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (t.amount > 0 && !t.isWash && !t.isPayday && !budget.beforeStart(t)) {
                val of = t.returnOf?.let { id -> budget.transactions.firstOrNull { it.id == id } }
                if (t.returnOf != null) {
                    Gap()
                    Text(
                        "A return of ${of?.payee?.ifBlank { null } ?: "a purchase"}" + (of?.let { " on ${it.date} (${Money.format(it.amount)})" } ?: "") +
                            ". The money went back to its envelopes. A return isn't income.",
                        color = DdfColors.Muted,
                    )
                    TextButton(onClick = onNotReturn) { Text("Not a return") }
                } else if (!pickReturn) {
                    OutlinedButton(onClick = { pickReturn = true }) { Text("This is a return") }
                } else {
                    SectionTitle("Return of which purchase?")
                    if (returnCandidates.isEmpty()) {
                        Text("No sorted purchase in the last 90 days has this much left to return.", color = DdfColors.Muted)
                    }
                    returnCandidates.take(20).forEach { c ->
                        ListRow(onClick = { pickReturn = false; onReturn(c.id) }) {
                            Row { Text("${c.payee.ifBlank { "No description" }}, ${c.date}", Modifier.weight(1f)); MoneyText(c.amount) }
                        }
                    }
                    TextButton(onClick = { pickReturn = false }) { Text("Cancel") }
                }
            }
            if (t.isWash) {
                Text("Pass-through money (a wash): it counts as neither income nor spending.", color = DdfColors.Muted)
                TextButton(onClick = onUnwash) { Text("Not a wash") }
            }
            if (t.amount > 0 && !t.isWash && t.returnOf == null) {
                Gap()
                if (t.isPayday) OutlinedButton(onClick = { onPayday(false) }) { Text("Unmark payday") }
                else Button(onClick = { onPayday(true) }) { Text("Mark as payday") }
                Text("Payday closes the budget month, sweeps Fill to target leftovers into Rollover, and fills envelopes in order.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (!t.isWash && !t.isPayday && washCandidates.isNotEmpty()) {
                SectionTitle("Pass it through (wash) with")
                washCandidates.forEach { c ->
                    ListRow(onClick = { onWash(c.id) }) { Row { Text("${c.payee.ifBlank { "No description" }}, ${c.date}", Modifier.weight(1f)); MoneyText(c.amount) } }
                }
            }
        }
        if (onMakeRule != null && t.kind == TransactionKind.NORMAL && !t.isWash) TextButton(onClick = onMakeRule) { Text("Make a rule from this") }
        if (message != null) Text(message, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 6.dp))
        Gap()
        if (!confirmDelete) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
        else Row { Text("Delete this transaction?", Modifier.weight(1f)); TextButton(onClick = onDelete) { Text("Delete") }; TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }
        Gap(24)
    }
}
