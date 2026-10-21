package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.Payday

/** The envelope list in fill order. Drag the handle to change the order (it's the payday fill order). */
@Composable
fun EnvelopesScreen(budget: Budget, onEnvelope: (String) -> Unit, onAdd: () -> Unit, onMove: () -> Unit, onReorder: (List<String>) -> Unit) {
    val list = budget.fillOrder
    val archived = budget.envelopes.filter { it.archived }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Envelopes", onBack = null) {
            TextButton(onClick = onMove) { Text("Move money") }
            TextButton(onClick = onAdd) { Text("Add") }
        }
        Text("Fill order, drag the handle to change it", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (list.size <= 1) EmptyState("No envelopes yet besides Rollover. Add one to give your money a job.")
        ReorderableList(list, budget, onEnvelope, onReorder)
        if (archived.isNotEmpty()) {
            SectionTitle("Archived")
            archived.forEach { e -> EnvelopeRow(e, budget, onClick = { onEnvelope(e.id) }) }
        }
        Gap(24)
    }
}

@Composable
private fun ReorderableList(list: List<EnvelopeEntity>, budget: Budget, onEnvelope: (String) -> Unit, onReorder: (List<String>) -> Unit) {
    val byId = list.associateBy { it.id }
    var order by remember(list.map { it.id }) { mutableStateOf(list.map { it.id }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    Column {
        order.forEach { id ->
            val e = byId[id] ?: return@forEach
            Box(
                Modifier
                    .onGloballyPositioned { heights[id] = it.size.height }
                    .zIndex(if (dragging == id) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging == id) offset else 0f },
            ) {
                EnvelopeRow(e, budget, onClick = { onEnvelope(id) }, handle = {
                    Text(
                        "≡", color = DdfColors.Muted, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(end = 12.dp).pointerInput(id) {
                            detectDragGestures(
                                onDragStart = { dragging = id; offset = 0f },
                                onDragEnd = { dragging = null; offset = 0f; onReorder(order) },
                                onDragCancel = { dragging = null; offset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    offset += amount.y
                                    val i = order.indexOf(id)
                                    val h = heights[id] ?: return@detectDragGestures
                                    if (offset > h / 2f && i < order.lastIndex) {
                                        order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                                        offset -= h
                                    } else if (offset < -h / 2f && i > 0) {
                                        order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                                        offset += h
                                    }
                                },
                            )
                        },
                    )
                })
            }
        }
    }
}

@Composable
fun EnvelopeDetailScreen(
    envelope: EnvelopeEntity,
    budget: Budget,
    today: LocalDate,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onCover: () -> Unit,
    onUseTarget: (Long) -> Unit,
    onArchive: (Boolean) -> Unit,
    onTransaction: (String) -> Unit,
) {
    val bal = budget.envelopeBalance(envelope.id)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(envelope.name, onBack) { if (!envelope.isSystem || envelope.mode == EnvelopeMode.CASH) TextButton(onClick = onEdit) { Text("Edit") } }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(modeName(envelope.mode), color = DdfColors.Muted)
                Text(Money.format(bal), style = MaterialTheme.typography.headlineMedium, color = if (bal < 0) DdfColors.Warning else DdfColors.Cyan)
                when (envelope.mode) {
                    EnvelopeMode.FILL_TO_TARGET -> Detail("Target", envelope.target?.let { Money.format(it) } ?: "No target")
                    EnvelopeMode.ADD_EVERY_PAYDAY, EnvelopeMode.SEASONAL -> Detail("Each payday", envelope.perPayday?.let { Money.format(it) } ?: "Not set")
                    EnvelopeMode.GOAL -> {
                        Detail("Goal", envelope.goalAmount?.let { Money.format(it) } ?: "Not set")
                        Detail("By", envelope.goalDate?.toString() ?: "Not set")
                        Detail("Each payday", Money.format(budget.goalPerPayday(envelope, today)))
                    }
                    EnvelopeMode.DEBT -> {
                        Detail("Still owed", budget.debtRemaining(envelope)?.let { Money.format(it) } ?: "Not entered yet")
                        Detail("Each payday", envelope.perPayday?.let { Money.format(it) } ?: "Not set")
                        envelope.requiredPayment?.let { Detail("Required payment", Money.format(it)) }
                        envelope.dueDate?.let { Detail("Due", it.toString()) }
                    }
                    EnvelopeMode.CASH -> Text("ATM withdrawals land here. Log what you spend in cash by hand.", color = DdfColors.Muted)
                    EnvelopeMode.ROLLOVER -> Text("Payday leftovers from Fill to target envelopes. Spend it on anything; nothing touches it by itself.", color = DdfColors.Muted)
                }
                if (envelope.mode != EnvelopeMode.ROLLOVER && envelope.mode != EnvelopeMode.CASH) {
                    val want = Payday.amountFor(envelope, bal, budget, today)
                    if (want > 0) Detail("Next payday adds", Money.format(want))
                }
            }
        }
        budget.canCoverFromRollover(envelope)?.let { amount ->
            Gap()
            Button(onClick = onCover, modifier = Modifier.fillMaxWidth()) { Text("Cover ${Money.format(amount)} from Rollover") }
        }
        budget.suggestedTarget(envelope)?.let { avg ->
            Gap()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("You spend about ${Money.format(avg)} here each budget month.")
                    TextButton(onClick = { onUseTarget(avg) }) { Text("Use it as the target") }
                }
            }
        }
        Gap()
        Row {
            OutlinedButton(onClick = onMove) { Text("Move money") }
            HGap()
            if (envelope.mode != EnvelopeMode.ROLLOVER) OutlinedButton(onClick = { onArchive(!envelope.archived) }) { Text(if (envelope.archived) "Unarchive" else "Archive") }
        }
        SectionTitle("Recent activity")
        val txById = budget.transactions.associateBy { it.id }
        val lines = budget.lines.filter { it.bucket == LineBucket.ENVELOPE && it.envelopeId == envelope.id && txById[it.transactionId] != null }
            .sortedByDescending { txById[it.transactionId]!!.date }.take(30)
        if (lines.isEmpty()) EmptyState("No transactions in this envelope yet.")
        lines.forEach { l ->
            val t = txById.getValue(l.transactionId)
            ListRow(onClick = { onTransaction(t.id) }) {
                Row { Column(Modifier.weight(1f)) { Text(t.payee.ifBlank { "No description" }); Text(t.date.toString(), color = DdfColors.Muted) }; MoneyText(l.amount) }
            }
        }
        Gap(24)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(label, color = DdfColors.Muted, modifier = Modifier.weight(1f)); Text(value) }
}

/** Create or edit an envelope: name, mode, and the amounts that mode uses. */
@Composable
fun EnvelopeEditScreen(existing: EnvelopeEntity?, error: String?, onBack: () -> Unit, onSave: (BudgetActions.EnvelopeDraft) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var mode by remember { mutableStateOf(existing?.mode ?: EnvelopeMode.FILL_TO_TARGET) }
    var target by remember { mutableStateOf(existing?.target?.let { Money.plain(it) } ?: "") }
    var perPayday by remember { mutableStateOf(existing?.perPayday?.let { Money.plain(it) } ?: "") }
    var goal by remember { mutableStateOf(existing?.goalAmount?.let { Money.plain(it) } ?: "") }
    var goalDate by remember { mutableStateOf(existing?.goalDate?.toString() ?: "") }
    var owed by remember { mutableStateOf(existing?.debtOwed?.let { Money.plain(it) } ?: "") }
    var required by remember { mutableStateOf(existing?.requiredPayment?.let { Money.plain(it) } ?: "") }
    var due by remember { mutableStateOf(existing?.dueDate?.toString() ?: "") }
    var everyday by remember { mutableStateOf(existing?.everyday ?: false) }
    var reminder by remember { mutableStateOf(existing?.reminder ?: false) }
    var reminderDays by remember { mutableIntStateOf(existing?.reminderDays ?: 2) }
    val system = existing?.isSystem == true
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(if (existing == null) "New envelope" else "Edit envelope", onBack)
        TextFieldRow("Name", name, { name = it })
        if (!system) {
            Picker("How it fills on payday", EnvelopeMode.entries.filter { it != EnvelopeMode.ROLLOVER && it != EnvelopeMode.CASH }.map { it to modeName(it) }, mode, { mode = it })
            when (mode) {
                EnvelopeMode.FILL_TO_TARGET -> {
                    AmountField("Target (optional)", target, { target = it })
                    Text("Each payday tops it up to the target.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                EnvelopeMode.ADD_EVERY_PAYDAY -> AmountField("Add each payday", perPayday, { perPayday = it })
                EnvelopeMode.GOAL -> {
                    AmountField("Goal amount", goal, { goal = it })
                    DateField("Reach it by", goalDate, { goalDate = it })
                    Text("The app works out the amount per payday and stops at the goal.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                EnvelopeMode.SEASONAL -> {
                    AmountField("Add each payday", perPayday, { perPayday = it })
                    Text("Once bills are linked (a later version), it adds their 12-month average instead.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                EnvelopeMode.DEBT -> {
                    AmountField("Balance owed (optional until known)", owed, { owed = it })
                    AmountField("Add each payday", perPayday, { perPayday = it })
                    AmountField("Required payment (optional)", required, { required = it })
                    DateField("Due date (optional)", due, { due = it })
                    SettingRow("Remind me", "With a required payment and due date, a reminder like a bill's, each month. Off by default.", reminder, true, { reminder = it })
                    if (reminder) Picker("How early", listOf(0 to "On the day", 1 to "1 day before", 2 to "2 days before", 3 to "3 days before", 7 to "A week before"), reminderDays, { reminderDays = it })
                }
                else -> {}
            }
            SettingRow("Everyday spending", "Counts in Safe to Spend, like groceries and gas. Alerts warn when Safe to Spend would go negative.", everyday, true, { everyday = it })
        }
        ErrorLine(error)
        Gap()
        Button(
            onClick = {
                fun amt(s: String) = if (s.isBlank()) null else Money.parse(s)
                onSave(
                    BudgetActions.EnvelopeDraft(
                        name = name, mode = mode,
                        target = if (mode == EnvelopeMode.FILL_TO_TARGET) amt(target) else null,
                        perPayday = if (mode in setOf(EnvelopeMode.ADD_EVERY_PAYDAY, EnvelopeMode.SEASONAL, EnvelopeMode.DEBT)) amt(perPayday) else null,
                        goalAmount = if (mode == EnvelopeMode.GOAL) amt(goal) else null,
                        goalDate = if (mode == EnvelopeMode.GOAL) parseDateOrNull(goalDate) else null,
                        debtOwed = if (mode == EnvelopeMode.DEBT) amt(owed) else null,
                        requiredPayment = if (mode == EnvelopeMode.DEBT) amt(required) else null,
                        dueDate = if (mode == EnvelopeMode.DEBT) parseDateOrNull(due) else null,
                        everyday = if (system) null else everyday,
                        reminder = if (mode == EnvelopeMode.DEBT) reminder else null,
                        reminderDays = if (mode == EnvelopeMode.DEBT) reminderDays else null,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        Gap(24)
    }
}

/** Move money between envelopes, or between an envelope and Ready to Assign (null). Rollover included. */
@Composable
fun MoveMoneyScreen(
    budget: Budget, fromId: String?, error: String?, onBack: () -> Unit,
    /** Over-assigned: start as a move from the fullest envelope back into Ready to Assign, for what's over. */
    backToReady: Boolean = false,
    onMove: (String?, String?, Long) -> Unit,
) {
    val options: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to "Ready to assign (${Money.format(budget.readyToAssign)})") +
        budget.fillOrder.map { it.id to "${it.name} (${Money.format(budget.envelopeBalance(it.id))})" }
    val fullest = budget.fillOrder.maxByOrNull { budget.envelopeBalance(it.id) }?.id
    var from by remember { mutableStateOf(if (backToReady) fullest else fromId) }
    var to by remember { mutableStateOf<String?>(if (backToReady) null else budget.fillOrder.firstOrNull { it.id != fromId }?.id) }
    var amount by remember { mutableStateOf(if (backToReady && budget.readyToAssign < 0) Money.plain(-budget.readyToAssign) else "") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Move money", onBack)
        if (backToReady && budget.readyToAssign < 0) {
            Text("Ready to assign is ${Money.format(budget.readyToAssign)}: more is in envelopes than you have. Move that much back.", color = DdfColors.Warning)
        }
        Picker("From", options, from, { from = it })
        Picker("To", options, to, { to = it })
        AmountField("Amount", amount, { amount = it })
        ErrorLine(error)
        Gap()
        Button(onClick = { onMove(from, to, Money.parse(amount) ?: 0) }, modifier = Modifier.fillMaxWidth()) { Text("Move") }
    }
}

/**
 * Assign: Ready to Assign, the hints from recent deposits' notes (never assigned by themselves), and the envelopes
 * still short in fill order, each with Add (up to what it wants) and a custom amount.
 */
@Composable
fun AssignScreen(
    budget: Budget,
    today: LocalDate,
    error: String?,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onAssignAmount: (String, Long) -> Unit,
    shortfalls: List<Payday.Shortfall>?,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Assign", onBack)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Ready to assign", color = DdfColors.Muted)
                Text(Money.format(budget.readyToAssign), style = MaterialTheme.typography.headlineSmall, color = if (budget.readyToAssign < 0) DdfColors.Warning else DdfColors.Cyan)
                val lastPayday = budget.paydayDates.lastOrNull()
                val hints = budget.transactions.filter { t ->
                    t.amount > 0 && !t.isPayday && !t.isWash && (t.memo.isNotBlank()) &&
                        (lastPayday == null || !t.date.isBefore(lastPayday)) &&
                        budget.lines.any { it.transactionId == t.id && it.bucket == LineBucket.READY_TO_ASSIGN }
                }.take(5)
                hints.forEach { t -> Text("From ${t.payee.ifBlank { "a deposit" }}, note \"${t.memo}\" (${Money.format(t.amount)})", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (shortfalls != null && shortfalls.isNotEmpty()) {
            SectionTitle("Didn't fill on payday")
            shortfalls.forEach { s ->
                val e = budget.envelopes.firstOrNull { it.id == s.envelopeId } ?: return@forEach
                Row { Text(e.name, Modifier.weight(1f)); Text("short ${Money.format(s.short)}", color = DdfColors.Warning) }
            }
        }
        ErrorLine(error)
        SectionTitle("Envelopes still short, in fill order")
        val short = budget.fillOrder.filter { e -> Payday.amountFor(e, budget.envelopeBalance(e.id), budget, today) > 0 }
        if (short.isEmpty()) EmptyState("Every envelope has what it needs.")
        short.forEach { e ->
            var custom by remember(e.id) { mutableStateOf("") }
            val want = Payday.amountFor(e, budget.envelopeBalance(e.id), budget, today)
            ListRow(onClick = null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name)
                        Text("short ${Money.format(want)}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onAdd(e.id) }) { Text("Add") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { AmountField("Other amount", custom, { custom = it }) }
                    TextButton(onClick = { Money.parse(custom)?.let { onAssignAmount(e.id, it) }; custom = "" }) { Text("Assign") }
                }
            }
        }
        Gap(24)
    }
}
