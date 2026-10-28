package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BillRecurrence
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.PaymentMethod
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.Bills
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Forecast
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.Paydays
import net.darkrelay.deaddropfinance.domain.Recurring

private val dayFormat = DateTimeFormatter.ofPattern("MMM d")
fun dayText(d: LocalDate): String = d.format(dayFormat)

// ---- Bills tab ------------------------------------------------------------------------------------------------------

/** One line in Coming up: a bill's unpaid cycle, or a debt envelope's payment due. */
data class DueItem(val name: String, val due: LocalDate, val amount: Long, val autopay: Boolean, val reminder: Boolean, val partialPaid: Long, val billId: String?, val envelopeId: String?)

fun comingUp(b: Budget, today: LocalDate): List<DueItem> {
    val until = today.plusDays(45)
    val bills = Bills.comingUp(b.bills, b.billPayments, until).map { c ->
        DueItem(c.bill.name, c.due, c.remaining, c.bill.autopay, c.bill.reminder, c.paid, c.bill.id, c.bill.envelopeId)
    }
    val debts = b.envelopes.filter { it.mode == EnvelopeMode.DEBT && !it.archived && it.requiredPayment != null }.mapNotNull { e ->
        Bills.nextDebtDue(b, e, today)?.takeIf { !it.isAfter(until) }?.let { DueItem(e.name, it, e.requiredPayment!!, false, e.reminder, 0, null, e.id) }
    }
    return (bills + debts).sortedBy { it.due }
}

@Composable
fun BillsScreen(
    budget: Budget, today: LocalDate, prediction: Paydays.Prediction, series: List<Recurring.Series>,
    onBill: (String) -> Unit, onEnvelope: (String) -> Unit, onAdd: () -> Unit, onSubscriptions: () -> Unit, onPayday: (LocalDate?) -> Unit,
    editingPayday: Boolean = false,
) {
    val next = prediction.effectiveNext(today)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Bills", onBack = null) { TextButton(onClick = onAdd) { Text("Add") } }
        PaydayLine(prediction, today, onPayday, editingPayday)
        if (next != null) {
            Bills.autopayChecks(budget, budget.billPayments, today, next).groupBy { it.cycle.bill.envelopeId }.values.forEach { group ->
                val w = group.first()
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text("Autopay check", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(if (w.short > 0) "short ${Money.format(w.short)}" else "covered", color = if (w.short > 0) DdfColors.Warning else DdfColors.Cyan)
                        }
                        Text(
                            group.joinToString("; ") { "${it.cycle.bill.name} pulls ${Money.format(it.cycle.remaining)} on ${dayText(it.cycle.due)}" } +
                                ". ${w.envelope?.name ?: "The envelope"} has ${Money.format(w.envelopeHas)}.",
                            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        SectionTitle("Coming up")
        val items = comingUp(budget, today)
        if (budget.bills.isEmpty()) {
            EmptyState("No bills yet. Add one, or make one from a repeating charge in Subscriptions.")
        } else if (items.isEmpty()) Text("Nothing unpaid in the next 45 days.", color = DdfColors.Muted)
        items.forEach { i ->
            ListRow(onClick = { i.billId?.let(onBill) ?: i.envelopeId?.let(onEnvelope) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row { Text(i.name); if (i.autopay) Pill("autopay"); if (i.billId == null) Pill("debt"); if (i.reminder) Pill("reminder") }
                        Text(
                            (if (i.due.isBefore(today)) "was due ${dayText(i.due)}, overdue" else "due ${dayText(i.due)}") +
                                (if (i.partialPaid > 0) ", ${Money.format(i.partialPaid)} paid so far" else ""),
                            color = if (i.due.isBefore(today)) DdfColors.Warning else DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    MoneyText(i.amount)
                }
            }
        }
        Text("Reminders are off unless you turn them on for a bill.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        val live = series.filter { !it.dismissed }
        SectionTitle("Subscriptions") { TextButton(onClick = onSubscriptions) { Text("All") } }
        ListRow(onSubscriptions) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("${live.size} repeating charge${if (live.size == 1) "" else "s"}" + live.count { it.isNew }.let { if (it > 0) ", $it new" else "" })
                    Text("Monthly, every 3 or 6 months, and yearly", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("${Money.format(Recurring.monthlyTotal(live))}/mo", color = DdfColors.Muted)
            }
        }
        val suggest = live.count { it.subscription?.billId == null && budget.bills.none { b -> b.matchText != null && it.keys.any { k -> net.darkrelay.deaddropfinance.domain.Payees.contains(k, b.matchText) } } }
        if (suggest > 0) {
            Text("$suggest of them could be bills: open one and tap Make it a bill.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Gap(24)
    }
}

/** The next payday line: predicted (or set by the user), with Change (right here), and a quiet note when it's late. */
@Composable
fun PaydayLine(p: Paydays.Prediction, today: LocalDate, onChange: (LocalDate?) -> Unit, editing: Boolean = false) {
    val next = p.next
    var edit by remember { mutableStateOf(editing) }
    var text by remember(next) { mutableStateOf(next?.toString() ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(if (next == null) "Next payday: mark a deposit as payday first" else "Next payday: ${dayText(next)}")
            Text(
                when {
                    next == null -> "Payday is the deposit landing, never a date on the calendar."
                    p.corrected -> "Set by you"
                    p.guessed -> "Guessed from one payday (two weeks). Correct it if it's different."
                    p.monthlyDay != null -> "Predicted: monthly, around the ${ordinal(p.monthlyDay)}"
                    else -> "Predicted: every ${p.intervalDays} days"
                },
                color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (next != null && !edit) TextButton(onClick = { edit = true }) { Text("Change") }
    }
    if (edit) {
        DateField("Next payday", text, { text = it })
        Text("When you expect the deposit. Payday still starts only when it lands.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { parseDateOrNull(text)?.let { onChange(it); edit = false } }) { Text("Set") }
            if (p.corrected) TextButton(onClick = { onChange(null); edit = false }) { Text("Use the prediction") }
            TextButton(onClick = { edit = false }) { Text("Cancel") }
        }
    }
    if (p.late && next != null) LatePaydayNote(next, today)
}

@Composable
fun LatePaydayNote(expected: LocalDate, today: LocalDate) {
    Text(
        "Payday was expected ${dayText(expected)} and hasn't landed yet. Nothing to do: it starts when the deposit comes in, and the forecast counts it as tomorrow until then.",
        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
    )
}

fun ordinal(n: Int): String = n.toString() + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

// ---- one bill ---------------------------------------------------------------------------------------------------------

@Composable
fun BillDetailScreen(
    bill: BillEntity, budget: Budget, today: LocalDate, message: String?,
    onBack: () -> Unit, onEdit: () -> Unit, onPaid: (LocalDate) -> Unit, onCash: (LocalDate, String) -> Unit, onUnpay: (LocalDate) -> Unit,
    onDebt: (LocalDate) -> Unit, onTransaction: (String) -> Unit,
) {
    val months = bill.recurrence.months?.toLong() ?: 0
    val cycles = Bills.cycles(bill, budget.billPayments, today.plusMonths(maxOf(1, months))).takeLast(6).reversed()
    val cash = budget.accounts.filter { it.type == AccountType.CASH && !it.archived }
    val env = budget.envelopes.firstOrNull { it.id == bill.envelopeId }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(bill.name, onBack) { TextButton(onClick = onEdit) { Text("Edit") } }
        Text(
            "${Money.format(bill.amountDue)}${if (bill.varies) " (changes: the latest charge is expected)" else ""}, ${Bills.every(bill)}" +
                (env?.let { ", from ${it.name}" } ?: "") + if (bill.autopay) ", autopay" else "",
            color = DdfColors.Muted,
        )
        Text(
            if (bill.reminder) "Reminder ${bill.reminderDays} day${if (bill.reminderDays == 1) "" else "s"} before it's due." else "No reminder (turn it on in Edit).",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        if (message != null) Text(message, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 6.dp))
        cycles.forEach { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text("Due ${dayText(c.due)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(
                            when { c.isPaid -> "paid"; c.partial -> "${Money.format(c.remaining)} left"; c.due.isBefore(today) -> "overdue"; else -> Money.format(c.expected) },
                            color = when { c.isPaid -> DdfColors.Cyan; c.due.isBefore(today) || c.partial -> DdfColors.Warning; else -> MaterialTheme.colorScheme.onSurface },
                        )
                    }
                    c.payments.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                "${dayText(p.date)}, " + when (p.method) {
                                    PaymentMethod.BANK -> "bank"; PaymentMethod.HAND -> "ticked by hand"; PaymentMethod.CASH -> "cash"; PaymentMethod.DEBT -> "rest moved to debt"
                                },
                                Modifier.weight(1f), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                            )
                            Text(Money.format(p.amount), style = MaterialTheme.typography.bodySmall)
                            if (p.transactionId != null) TextButton(onClick = { onTransaction(p.transactionId) }) { Text("Open") }
                        }
                    }
                    if (!c.isPaid) {
                        Row {
                            TextButton(onClick = { onPaid(c.due) }) { Text("Mark paid") }
                            if (cash.isNotEmpty()) TextButton(onClick = { onCash(c.due, cash.first().id) }) { Text("Paid in cash") }
                            if (c.partial) TextButton(onClick = { onDebt(c.due) }) { Text("Rest to debt") }
                        }
                    } else if (c.payments.any { it.method == PaymentMethod.HAND }) {
                        TextButton(onClick = { onUnpay(c.due) }) { Text("Not paid after all") }
                    }
                }
            }
        }
        Text(
            "Paid in parts? Each payment counts, and the bill is paid when they add up. CSV import ticks it when the payment matches.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        Gap(24)
    }
}

/** A starting point for a new bill (from a repeating charge). */
data class BillPrefill(val name: String, val amount: Long, val due: LocalDate, val recurrence: BillRecurrence, val matchText: String)

@Composable
fun BillEditScreen(
    existing: BillEntity?, prefill: BillPrefill?, budget: Budget, error: String?,
    onBack: () -> Unit, onSave: (BillActions.BillDraft) -> Unit, onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name ?: prefill?.name ?: "") }
    var amount by remember { mutableStateOf((existing?.amountDue ?: prefill?.amount)?.let { Money.plain(it) } ?: "") }
    var due by remember { mutableStateOf((existing?.dueDate ?: prefill?.due)?.toString() ?: "") }
    var rec by remember { mutableStateOf(existing?.recurrence?.let { if (it == BillRecurrence.IRREGULAR) BillRecurrence.NONE else it } ?: prefill?.recurrence ?: BillRecurrence.MONTHLY) }
    var env by remember { mutableStateOf(existing?.envelopeId) }
    var autopay by remember { mutableStateOf(existing?.autopay ?: false) }
    var reminder by remember { mutableStateOf(existing?.reminder ?: false) }
    var days by remember { mutableIntStateOf(existing?.reminderDays ?: 2) }
    var match by remember { mutableStateOf(existing?.matchText ?: prefill?.matchText ?: "") }
    var anyAmount by remember { mutableStateOf(existing?.anyAmount ?: false) }
    var varies by remember { mutableStateOf(existing?.varies ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(if (existing == null) "New bill" else "Edit bill", onBack)
        TextFieldRow("Name", name, { name = it })
        AmountField("Amount", amount, { amount = it })
        SettingRow("The amount changes", "Like a utility bill: the latest charge is what's expected next time.", varies, true, { varies = it })
        DateField(if (existing == null) "Next due" else "First due", due, { due = it })
        Picker(
            "Repeats",
            listOf(BillRecurrence.MONTHLY to "Monthly", BillRecurrence.QUARTERLY to "Every 3 months", BillRecurrence.SEMIANNUAL to "Every 6 months", BillRecurrence.YEARLY to "Yearly", BillRecurrence.NONE to "Irregular or one-off"),
            rec, { rec = it },
        )
        Picker("Paid from envelope", listOf<Pair<String?, String>>(null to "No envelope") + budget.fillOrder.map { it.id to it.name }, env, { env = it })
        Text("Several bills can feed one envelope. A yearly or irregular bill's envelope with no target fills a little each payday.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        SettingRow("Autopay", "You do nothing; the app warns when it's due before payday and the envelope is short.", autopay, true, { autopay = it })
        SettingRow("Remind me", "Off by default. A notification before it's due.", reminder, true, { reminder = it })
        if (reminder) Picker("How early", listOf(0 to "On the day", 1 to "1 day before", 2 to "2 days before", 3 to "3 days before", 5 to "5 days before", 7 to "A week before"), days, { days = it })
        SectionTitle("Matching payments")
        TextFieldRow("Payment's description has", match, { match = it })
        SettingRow("Any amount counts", "Every payment to it counts toward the bill, whatever the amount (paid in parts, by transfers).", anyAmount, true, { anyAmount = it })
        Text("Rules can count toward a bill too (Rules, Counts toward bill).", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        ErrorLine(error)
        Gap()
        Button(
            onClick = {
                onSave(BillActions.BillDraft(name, Money.parse(amount)?.let { kotlin.math.abs(it) } ?: 0, parseDateOrNull(due), rec, env, autopay, reminder, days, match.ifBlank { null }, anyAmount, varies))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (onDelete != null) { Gap(); OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete bill", color = DdfColors.Error) } }
        Gap(24)
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, title = { Text("Delete this bill?") },
            text = { Text("The bill and its payment records go. The transactions themselves stay.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = DdfColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---- payday checklist (Home) ------------------------------------------------------------------------------------------

@Composable
fun PaydayChecklistCard(items: List<Bills.Cycle>, onTick: (Bills.Cycle, Boolean) -> Unit, onBill: (String) -> Unit) {
    if (items.isEmpty()) return
    val paid = items.count { it.isPaid }
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            if (paid == items.size) {
                Text("All ${items.size} payday bills paid", color = DdfColors.Cyan)
                return@Column
            }
            Row { Text("Pay these now", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Text("$paid of ${items.size} paid", color = DdfColors.Muted) }
            Progress(paid.toFloat() / items.size)
            items.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(c.isPaid, { on -> onTick(c, on) })
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.bill.name, Modifier.weight(1f, fill = false), color = if (c.isPaid) DdfColors.Muted else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (c.bill.autopay) Pill("autopay")
                        }
                        Text(
                            "due ${dayText(c.due)}" + if (c.partial) ", ${Money.format(c.remaining)} left" else "",
                            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onBill(c.bill.id) }) { Text(Money.format(if (c.isPaid) c.paid else c.remaining)) }
                }
            }
            Text("Tick a bill when you pay it. CSV import ticks them too.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---- subscriptions ------------------------------------------------------------------------------------------------------

@Composable
fun SubscriptionsScreen(series: List<Recurring.Series>, onBack: () -> Unit, onSeries: (String) -> Unit) {
    val live = series.filter { !it.dismissed }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Subscriptions", onBack)
        Row { Text("${live.size} repeating", Modifier.weight(1f), color = DdfColors.Muted); Text("${Money.format(Recurring.monthlyTotal(live))}/mo equivalent", color = DdfColors.Muted) }
        if (live.isEmpty()) EmptyState("No repeating charges found yet. They show up once a charge has repeated at a steady interval.")
        live.forEach { s -> SeriesRow(s, onSeries) }
        Text("Amounts come from the latest charge, so price changes show up on their own.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        val gone = series.filter { it.dismissed }
        if (gone.isNotEmpty()) {
            SectionTitle("Not subscriptions")
            gone.forEach { s -> SeriesRow(s, onSeries) }
        }
        Gap(24)
    }
}

@Composable
private fun SeriesRow(s: Recurring.Series, onSeries: (String) -> Unit) {
    ListRow(onClick = { onSeries(s.key) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row {
                    Text(s.name)
                    if (s.isNew && !s.dismissed) Pill("New")
                    s.priceChange?.let { Pill((if (it > 0) "up " else "down ") + Money.format(kotlin.math.abs(it))) }
                    if (s.subscription?.billId != null) Pill("bill")
                }
                Text(
                    "${s.interval.label}, last ${dayText(s.lastDate)}, next ${dayText(s.nextDate)}" +
                        if (s.interval != Recurring.Interval.MONTHLY) ", ${Money.format(s.monthly)}/mo" else "",
                    color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(Money.format(s.amount))
        }
    }
}

@Composable
fun SubscriptionDetailScreen(
    s: Recurring.Series, others: List<Recurring.Series>, budget: Budget, message: String?,
    onBack: () -> Unit, onConfirm: () -> Unit, onRename: (String) -> Unit, onMerge: (String) -> Unit, onMakeBill: (String?) -> Unit,
    onDismiss: () -> Unit, onRestore: () -> Unit,
) {
    var name by remember { mutableStateOf(s.name) }
    var env by remember { mutableStateOf<String?>(null) }
    var merge by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(s.name, onBack)
        Row { Text("${s.interval.label}, now ${Money.format(s.amount)}", Modifier.weight(1f)); if (s.isNew && !s.dismissed) Pill("New") }
        s.priceChange?.let { Text("Changed by ${Money.format(it)} since the charge before (${Money.format(s.previousAmount ?: 0)}).", color = DdfColors.Warning) }
        Text("Last charged ${dayText(s.lastDate)}; next expected ${dayText(s.nextDate)}." + if (s.interval != Recurring.Interval.MONTHLY) " ${Money.format(s.monthly)} a month." else "", color = DdfColors.Muted)
        if (message != null) Text(message, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 6.dp))
        if (s.isNew && !s.dismissed) Button(onClick = onConfirm) { Text("Yes, it's a subscription") }
        SectionTitle("Name")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TextFieldRow("Name", name, { name = it }) }
            TextButton(onClick = { onRename(name) }) { Text("Rename") }
        }
        if (s.subscription?.billId == null && !s.dismissed) {
            SectionTitle("Make it a bill")
            Picker("Paid from envelope", listOf<Pair<String?, String>>(null to "No envelope") + budget.fillOrder.map { it.id to it.name }, env, { env = it })
            OutlinedButton(onClick = { onMakeBill(env) }) { Text("Make it a bill") }
        }
        if (others.isNotEmpty() && !s.dismissed) {
            SectionTitle("Same thing under another name?")
            Picker("Merge with", others.map { it.key to it.name }, merge, { merge = it })
            merge?.let { m -> OutlinedButton(onClick = { onMerge(m) }) { Text("Merge") } }
        }
        SectionTitle("Charges")
        s.charges.reversed().take(12).forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(dayText(t.date), Modifier.weight(1f), color = DdfColors.Muted); MoneyText(t.amount) }
        }
        Gap()
        if (s.dismissed) TextButton(onClick = onRestore) { Text("It is a subscription after all") }
        else TextButton(onClick = onDismiss) { Text("Not a subscription", color = DdfColors.Error) }
        Gap(24)
    }
}

// ---- balance forecast ---------------------------------------------------------------------------------------------------

/** Plan (solid, cyan) and Usual pace (dashed, amber) with the zero line and the first day below zero. */
@Composable
fun ForecastChart(f: Forecast.Result, height: Int) {
    val lines = listOfNotNull(f.plan, f.usual)
    val all = lines.flatMap { l -> listOf(l.start) + l.days.map { it.balance } } + 0L
    val max = all.max(); val min = all.min()
    val cyan = DdfColors.Cyan; val warn = DdfColors.Warning; val outline = DdfColors.Outline; val bad = DdfColors.Error
    Canvas(Modifier.fillMaxWidth().height(height.dp).padding(vertical = 6.dp)) {
        val n = maxOf(1, f.plan.days.size - 1)
        fun x(i: Int) = size.width * i / n
        fun y(v: Long) = if (max == min) size.height / 2 else size.height * (max - v).toFloat() / (max - min).toFloat()
        drawLine(outline, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        fun draw(l: Forecast.Line, color: androidx.compose.ui.graphics.Color, dashed: Boolean) {
            l.days.zipWithNext().forEachIndexed { i, (a, b) ->
                drawLine(color, Offset(x(i), y(a.balance)), Offset(x(i + 1), y(b.balance)), strokeWidth = 5f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null)
            }
        }
        f.usual?.let { draw(it, warn, true) }
        draw(f.plan, cyan, false)
        (f.usual ?: f.plan).firstNegative?.let { d -> val i = (f.usual ?: f.plan).days.indexOf(d); drawCircle(bad, 9f, Offset(x(i), y(d.balance))) }
    }
}

@Composable
fun ForecastCard(f: Forecast.Result, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            ListRow(onOpen) {
                Text("Balance forecast to payday, ${dayText(f.payday)}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                Row { Text("Stick to the plan: land at "); MoneyText(f.plan.end, color = if (f.plan.end < 0) DdfColors.Error else DdfColors.Cyan) }
                ForecastChart(f, 64)
                val neg = f.usual?.firstNegative ?: f.plan.firstNegative
                Text(
                    when {
                        f.plan.firstNegative != null -> "Below zero on ${dayText(f.plan.firstNegative!!.date)} even on the plan. An overdraft is likely."
                        f.usual?.firstNegative != null -> "At your usual pace it goes below zero on ${dayText(neg!!.date)}."
                        f.usual == null -> "Stays above zero on the plan."
                        else -> "Stays above zero either way."
                    },
                    color = if (neg != null) DdfColors.Warning else DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun ForecastScreen(f: Forecast.Result, onBack: () -> Unit) {
    var usual by remember { mutableStateOf(f.usual != null) }
    val line = if (usual && f.usual != null) f.usual else f.plan
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Balance forecast", onBack)
        Text("Today to the day before payday (${dayText(f.payday)}), budget accounts only.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        ForecastChart(f, 160)
        Row {
            FilterChip(selected = !usual || f.usual == null, onClick = { usual = false }, label = { Text("Plan") })
            HGap()
            FilterChip(selected = usual && f.usual != null, onClick = { usual = true }, enabled = f.usual != null, label = { Text("Usual pace") })
        }
        f.usualWhyNot?.let { Text(it, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
        if (f.noEverydayEnvelopes) Text("No envelope is marked Everyday spending, so the plan has no day-to-day spending in it. Mark them on the envelope screen.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row { Text("Balance today", Modifier.weight(1f), color = DdfColors.Muted); MoneyText(line.start) }
                line.lowest?.let { lo -> Row { Text("Lowest point", Modifier.weight(1f), color = DdfColors.Muted); MoneyText(lo.balance); Text(", ${dayText(lo.date)}", color = DdfColors.Muted) } }
                Row { Text("Day before payday", Modifier.weight(1f), color = DdfColors.Muted); MoneyText(line.end) }
            }
        }
        line.firstNegative?.let { d ->
            Text(
                "Below zero on ${dayText(d.date)}. " + if (line === f.usual) "This line spends the way you did in recent budget months. The plan line is what happens if spending stays inside the envelopes." else "Even on the plan. Move money, or a bill may overdraw the account.",
                color = DdfColors.Warning,
            )
        }
        line.days.forEach { day ->
            ListRow(null) {
                Row { Text(dayText(day.date), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); MoneyText(day.balance, color = if (day.balance < 0) DdfColors.Error else null) }
                day.fixed.forEach { o -> Row { Text(o.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text("-" + Money.format(o.amount).removePrefix("-"), style = MaterialTheme.typography.bodySmall) } }
                if (day.spending > 0) Row { Text("Spending", Modifier.weight(1f), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall); Text("-" + Money.format(day.spending), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("Counts bills, autopays, subscriptions and debt payments on their dates. Paid bills drop off.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        Gap(24)
    }
}
