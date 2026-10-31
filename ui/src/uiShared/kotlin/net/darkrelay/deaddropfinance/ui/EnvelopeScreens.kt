package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.CategoryEntity
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.Payday

/** The envelope list in fill order. Drag the handle to change the order (it's the payday fill order). */
@Composable
fun EnvelopesScreen(budget: Budget, onEnvelope: (String) -> Unit, onAdd: () -> Unit, onMove: () -> Unit, onReorder: (List<String>) -> Unit, today: LocalDate = LocalDate.now()) {
    val list = budget.fillOrder
    val archived = budget.envelopes.filter { it.archived }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Envelopes", onBack = null) {
            TextButton(onClick = onMove) { Text("Move money") }
            TextButton(onClick = onAdd) { Text("Add") }
        }
        val month = budget.months(today).first()
        Text(assignedLine(budget.assignedIn(month), budget.incomeIn(month)), style = MaterialTheme.typography.titleSmall, color = DdfColors.Cyan)
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
        // keyed by envelope: a row keeps its drag gesture as it moves up or down the list (1.8.0)
        order.forEach { id ->
            val e = byId[id] ?: return@forEach
            androidx.compose.runtime.key(id) { Box(
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
            } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    /** Add activity (1.3.0): the Add transaction screen with this envelope chosen. */
    onAddActivity: () -> Unit = {},
    /** A move made by hand opens to edit (1.8.1). */
    onMoveRow: (String) -> Unit = {},
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
        // three buttons don't fit one line on a narrow phone, so they wrap
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddActivity) { Text("Add activity") }
            OutlinedButton(onClick = onMove) { Text("Move money") }
            if (envelope.mode != EnvelopeMode.ROLLOVER) OutlinedButton(onClick = { onArchive(!envelope.archived) }) { Text(if (envelope.archived) "Unarchive" else "Archive") }
        }
        SectionTitle("Activity")
        // every row, newest first, a page at a time so a long history doesn't slow the screen
        val rows = remember(budget, envelope.id) { budget.envelopeActivity(envelope.id) }
        var shown by rememberSaveable(envelope.id) { mutableIntStateOf(ACTIVITY_PAGE) }
        if (rows.isEmpty()) EmptyState("No transactions in this envelope yet.")
        rows.take(shown).forEach { r ->
            val sub = r.date.toString() + if (r.beforeBudgetStart) ", before budget start" else ""
            ListRow(onClick = r.transactionId?.let { id -> { onTransaction(id) } } ?: r.moveId?.let { id -> { onMoveRow(id) } }) {
                Row { Column(Modifier.weight(1f)) { Text(r.label); Text(sub, color = DdfColors.Muted) }; MoneyText(r.amount) }
            }
        }
        if (rows.size > shown) TextButton(onClick = { shown += ACTIVITY_PAGE }) { Text("Show more (${rows.size - shown} left)") }
        Gap(24)
    }
}

/** Rows an envelope's Activity list shows at a time. */
const val ACTIVITY_PAGE = 50

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(label, color = DdfColors.Muted, modifier = Modifier.weight(1f)); Text(value) }
}

/**
 * The categories an envelope can claim (1.1.0): every category, with the ones another envelope already claims named
 * (they can't be picked here), the ones this envelope claims now, and where the choice goes when it's saved.
 */
data class ClaimsUi(
    val categories: List<CategoryEntity>,
    /** Category id to the name of the other envelope that claims it. */
    val claimedElsewhere: Map<String, String>,
    val initial: Set<String>,
    val onChosen: (Set<String>) -> Unit,
)

/** Create or edit an envelope: name, mode, and the amounts that mode uses. [claims] adds the categories it claims. */
@Composable
fun EnvelopeEditScreen(existing: EnvelopeEntity?, error: String?, onBack: () -> Unit, onSave: (BudgetActions.EnvelopeDraft) -> Unit, claims: ClaimsUi? = null) {
    var claimed by remember { mutableStateOf(claims?.initial ?: emptySet()) }
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
                    Text("The ${LocalDeviceWords.current.product} works out the amount per payday and stops at the goal.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
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
            SettingRow("Everyday spending", "Counts in Safe to Spend, like groceries and gas.", everyday, true, { everyday = it })
        }
        if (claims != null && existing?.unplanned != true && !system) {
            SectionTitle("Categories this envelope claims")
            Text(
                "A purchase in a category goes to the envelope that claims it. A category nothing claims goes to Unplanned. Each category can be claimed by one envelope.",
                color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
            claims.categories.forEach { c ->
                val other = claims.claimedElsewhere[c.id]
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Checkbox(
                        checked = c.id in claimed, enabled = other == null,
                        onCheckedChange = { on -> claimed = if (on) claimed + c.id else claimed - c.id },
                    )
                    Column {
                        Text(c.name, color = if (other == null) DdfColors.Text else DdfColors.Muted)
                        if (other != null) Text("Claimed by $other", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        ErrorLine(error)
        Gap()
        Button(
            onClick = {
                fun amt(s: String) = if (s.isBlank()) null else Money.parse(s)
                claims?.onChosen(claimed)
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

/**
 * Move money between envelopes, or between an envelope and Ready to Assign (null). Rollover included. Opened from an
 * envelope's screen ([intoId]), it starts as Ready to Assign into that envelope (1.2.3); either side can be changed.
 */
@Composable
fun MoveMoneyScreen(
    budget: Budget, intoId: String?, error: String?, onBack: () -> Unit,
    /** Over-assigned: start as a move from the fullest envelope back into Ready to Assign, for what's over. */
    backToReady: Boolean = false,
    /** Edit a move (1.8.1): the move, its Save with the date, and Delete after the same question as a transaction's. */
    editing: MoveEntity? = null,
    onSaveEdit: (String?, String?, Long, LocalDate?) -> Unit = { _, _, _, _ -> },
    onDelete: () -> Unit = {},
    onMove: (String?, String?, Long) -> Unit,
) {
    // the move's own envelopes stay choosable even when archived
    val shown = budget.fillOrder + budget.envelopes.filter { e -> editing != null && (e.id == editing.fromEnvelopeId || e.id == editing.toEnvelopeId) && budget.fillOrder.none { it.id == e.id } }
    val options: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to "Ready to assign (${Money.format(budget.readyToAssign)})") +
        shown.map { it.id to "${it.name} (${Money.format(budget.envelopeBalance(it.id))})" }
    val fullest = budget.fillOrder.maxByOrNull { budget.envelopeBalance(it.id) }?.id
    var from by remember { mutableStateOf(if (editing != null) editing.fromEnvelopeId else if (backToReady) fullest else null) }
    var to by remember { mutableStateOf<String?>(if (editing != null) editing.toEnvelopeId else if (backToReady) null else intoId ?: budget.fillOrder.firstOrNull()?.id) }
    var amount by remember { mutableStateOf(if (editing != null) Money.plain(editing.amount) else if (backToReady && budget.readyToAssign < 0) Money.plain(-budget.readyToAssign) else "") }
    var date by remember { mutableStateOf(editing?.date?.toString() ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(if (editing != null) "Edit move" else "Move money", onBack)
        if (editing == null && backToReady && budget.readyToAssign < 0) {
            Text("Ready to assign is ${Money.format(budget.readyToAssign)}: more is in envelopes than you have. Move that much back.", color = DdfColors.Warning)
        }
        if (editing != null) DateField("Date", date, { date = it })
        Picker("From", options, from, { from = it })
        Picker("To", options, to, { to = it })
        AmountField("Amount", amount, { amount = it })
        ErrorLine(error)
        Gap()
        if (editing == null) Button(onClick = { onMove(from, to, Money.parse(amount) ?: 0) }, modifier = Modifier.fillMaxWidth()) { Text("Move") }
        else {
            Button(onClick = { onSaveEdit(from, to, Money.parse(amount) ?: 0, parseDateOrNull(date)) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            Gap()
            if (!confirmDelete) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            else Row { Text("Delete this move?", Modifier.weight(1f)); TextButton(onClick = onDelete) { Text("Delete") }; TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }
        }
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

/** The line at the top of Envelopes: how much of this budget month's income is assigned to envelopes. */
fun assignedLine(assigned: Long, income: Long): String = "${Money.format(assigned)} of ${Money.format(income)} assigned"
