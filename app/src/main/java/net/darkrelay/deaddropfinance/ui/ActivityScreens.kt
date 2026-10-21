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

/** Activity: the plain To sort list (the full approve flow is Stage 8) and recent transactions. */
@Composable
fun ActivityScreen(
    budget: Budget,
    onAdd: () -> Unit,
    onTransaction: (String) -> Unit,
    onSort: (String, String?, Boolean) -> Unit,
    onSplit: (String) -> Unit,
    syncNotes: List<String> = emptyList(),
) {
    val accounts = budget.accounts.associateBy { it.id }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Activity", onBack = null) { TextButton(onClick = onAdd, enabled = budget.accounts.any { !it.archived }) { Text("Add") } }
        if (budget.accounts.none { !it.archived }) EmptyState("No accounts yet. Add one in More to get started.")
        if (syncNotes.isNotEmpty()) {
            SectionTitle("From bank sync")
            syncNotes.forEach { Text(it, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        SectionTitle("To sort")
        if (budget.toSort.isEmpty()) EmptyState("Nothing to sort.")
        budget.toSort.forEach { t ->
            ListRow(onClick = { onTransaction(t.id) }) {
                Row { Column(Modifier.weight(1f)) { Text(t.payee.ifBlank { "No description" }); Text("${t.date}, ${accounts[t.accountId]?.name ?: ""}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }; MoneyText(t.amount) }
                val options: List<Pair<String?, String>> =
                    (if (t.amount > 0) listOf<Pair<String?, String>>(null to "Ready to assign") else emptyList()) +
                        budget.fillOrder.map { it.id to it.name }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Picker("Put it in", options, selected = "unset-${t.id}", onSelect = { id -> onSort(t.id, id, id == null) })
                    }
                    TextButton(onClick = { onSplit(t.id) }) { Text("Split") }
                }
            }
        }
        SectionTitle("Recent")
        val recent = budget.transactions.filter { t -> budget.toSort.none { it.id == t.id } }.take(100)
        if (recent.isEmpty()) EmptyState("No transactions yet. Add one, or import a CSV from your bank in More.")
        recent.forEach { t -> TransactionRow(t, budget, onClick = { onTransaction(t.id) }) }
        Gap(24)
    }
}

@Composable
fun TransactionRow(t: TransactionEntity, budget: Budget, onClick: () -> Unit) {
    val accounts = budget.accounts.associateBy { it.id }
    val envelopes = budget.envelopes.associateBy { it.id }
    val sub = when {
        t.kind == TransactionKind.TRANSFER -> "Transfer to ${accounts[t.transferAccountId]?.name ?: "?"}"
        t.isWash -> "Wash (pass-through)"
        else -> {
            val ls = budget.lines.filter { it.transactionId == t.id }
            if (ls.size > 1) "Split, ${ls.size} parts" else when (ls.firstOrNull()?.bucket) {
                LineBucket.ENVELOPE -> envelopes[ls.first().envelopeId]?.name ?: ""
                LineBucket.READY_TO_ASSIGN -> "Ready to assign"
                else -> "To sort"
            }
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
) {
    val accounts = budget.accounts.associateBy { it.id }
    val envelopes = budget.envelopes.associateBy { it.id }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(t.payee.ifBlank { if (t.kind == TransactionKind.TRANSFER) "Transfer" else "Transaction" }, onBack) { TextButton(onClick = onEdit) { Text("Edit") } }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                MoneyText(t.amount, bold = true)
                Text("${t.date}, ${accounts[t.accountId]?.name ?: ""}" + (if (t.kind == TransactionKind.TRANSFER) " to ${accounts[t.transferAccountId]?.name ?: ""}" else ""), color = DdfColors.Muted)
                if (t.memo.isNotBlank()) Text(t.memo)
                if (t.pending) Text("Pending: when the bank's version is imported, it takes this one's place.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (t.fee) Text("Tagged as an ATM fee.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                if (t.feeRebate) Text("Tagged as a fee rebate.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (t.kind == TransactionKind.NORMAL) {
            SectionTitle("Where it went")
            budget.lines.filter { it.transactionId == t.id }.sortedBy { it.sortOrder }.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(when (l.bucket) { LineBucket.ENVELOPE -> envelopes[l.envelopeId]?.name ?: "?"; LineBucket.READY_TO_ASSIGN -> "Ready to assign"; LineBucket.UNSORTED -> "To sort"; LineBucket.OFF_BUDGET -> "Outside the budget (track only)" }, Modifier.weight(1f))
                    MoneyText(l.amount)
                }
            }
            if (t.isWash) {
                Text("Pass-through money (a wash): it counts as neither income nor spending.", color = DdfColors.Muted)
                TextButton(onClick = onUnwash) { Text("Not a wash") }
            }
            if (t.amount > 0 && !t.isWash) {
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
        if (message != null) Text(message, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 6.dp))
        Gap()
        if (!confirmDelete) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
        else Row { Text("Delete this transaction?", Modifier.weight(1f)); TextButton(onClick = onDelete) { Text("Delete") }; TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }
        Gap(24)
    }
}
