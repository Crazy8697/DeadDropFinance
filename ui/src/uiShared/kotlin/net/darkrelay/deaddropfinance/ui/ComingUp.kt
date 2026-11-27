package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BillMonths
import net.darkrelay.deaddropfinance.domain.Bills
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetMonths
import net.darkrelay.deaddropfinance.domain.Money

/**
 * Bills, Coming up, one budget month at a time (phone 1.9.9, program 0.15.12). The month picker's place: [offset]
 * budget months from the one that holds today, or a custom range from [from] to [to] (both included).
 */
class ComingUpState {
    var offset by mutableIntStateOf(0)
    var custom by mutableStateOf(false)
    var from by mutableStateOf("")
    var to by mutableStateOf("")
}

/** What Coming up shows: one budget month ([current]: the one that holds today), or a range of days. */
sealed interface ComingUpView {
    data class Month(val window: BudgetMonths.Window, val current: Boolean) : ComingUpView
    data class Range(val from: LocalDate, val to: LocalDate) : ComingUpView
}

/** The view [s] asks for; a custom range whose dates don't read yet shows the month instead. */
fun comingUpView(b: Budget, today: LocalDate, s: ComingUpState): ComingUpView {
    val months = b.budgetMonths(today)
    val now = months.containing(today)
    if (s.custom) {
        val f = parseDateOrNull(s.from); val t = parseDateOrNull(s.to)
        if (f != null && t != null && !t.isBefore(f)) return ComingUpView.Range(f, t)
    }
    return ComingUpView.Month(months.step(now, s.offset), s.offset == 0)
}

/**
 * One line in Coming up: a bill's unpaid occurrence, or a debt envelope's payment due. [monthStart] is the budget month
 * the occurrence falls in (its This month box); [typed] the amount typed for that month; [lastChecked] when the bill's
 * box was last saved, any month.
 */
data class DueItem(
    val name: String, val due: LocalDate, val amount: Long, val autopay: Boolean, val reminder: Boolean, val partialPaid: Long,
    val billId: String?, val envelopeId: String?,
    val monthStart: LocalDate? = null, val typed: Long? = null, val lastChecked: Long? = null,
)

/**
 * Coming up's lines (1.9.9). A month: each bill's earliest unpaid occurrence due in it, and, in the month that holds
 * today, every unpaid occurrence from before, overdue. A range: every unpaid occurrence due in it. An amount typed for
 * an occurrence's month counts for it. Debt payments the same way.
 */
fun comingUp(b: Budget, today: LocalDate, view: ComingUpView): List<DueItem> {
    val months = b.budgetMonths(today)
    val (from, until) = when (view) {
        is ComingUpView.Month -> view.window.start to view.window.end.minusDays(1)
        is ComingUpView.Range -> view.from to view.to
    }
    val bills = b.bills.filter { !it.deleted }.flatMap { bill ->
        val unpaid = Bills.cycles(bill, b.billPayments, until, b.billAmountOf(bill, today)).filter { !it.isPaid }
        val shown = when (view) {
            is ComingUpView.Month -> (if (view.current) unpaid.filter { it.due.isBefore(from) } else emptyList()) + listOfNotNull(unpaid.firstOrNull { !it.due.isBefore(from) })
            is ComingUpView.Range -> unpaid.filter { !it.due.isBefore(from) }
        }
        shown.map { c ->
            val w = months.containing(c.due)
            DueItem(bill.name, c.due, c.remaining, bill.autopay, bill.reminder, c.paid, bill.id, bill.envelopeId,
                w.start, BillMonths.amountFor(b.billMonths, bill.id, w), BillMonths.lastChecked(b.billMonths, bill.id))
        }
    }
    val debts = b.envelopes.filter { it.mode == EnvelopeMode.DEBT && !it.archived && it.requiredPayment != null }.mapNotNull { e ->
        Bills.nextDebtDue(b, e, if (view is ComingUpView.Month && view.current) today else from)?.takeIf { !it.isAfter(until) }
            ?.let { DueItem(e.name, it, e.requiredPayment!!, false, e.reminder, 0, null, e.id) }
    }
    return (bills + debts).sortedWith(compareBy<DueItem> { it.due }.thenBy { it.name })
}

/** "3 unpaid this month", "5 unpaid in this range": the Coming up card's count line. */
fun comingUpCount(view: ComingUpView, n: Int) = if (view is ComingUpView.Month) "$n unpaid this month" else "$n unpaid in this range"

/** The month picker's label: "Dec 2026", or the range. */
fun comingUpLabel(view: ComingUpView) = when (view) {
    is ComingUpView.Month -> view.window.label
    is ComingUpView.Range -> "${dayText(view.from)} to ${dayText(view.to)}"
}

/** "Nov 27", the date a bill's box was last saved; empty if never. */
fun lastCheckedText(ms: Long?): String = ms?.let { dayText(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()) } ?: ""

/**
 * The This month box (1.9.9): empty until an amount is typed for the month; saved when Enter (Done) is pressed or the
 * box is left, exact as typed (never rounded). Emptying it and saving goes back to the bill's normal amount. Something
 * that isn't an amount shows a red border and isn't saved.
 */
@Composable
fun MonthAmountBox(typed: Long?, onSave: (Long?) -> Unit, tag: String, modifier: Modifier = Modifier, width: Dp = 104.dp, fontSize: Int = 13) {
    val saved = typed?.let { Money.plain(it) } ?: ""
    var text by remember(tag, saved) { mutableStateOf(saved) }
    var focused by remember { mutableStateOf(false) }
    val parsed: Long? = text.trim().let { if (it.isEmpty()) null else Money.parse(it) }
    val bad = text.isNotBlank() && (parsed == null || parsed < 0)
    fun save() { if (!bad && text.trim() != saved) onSave(parsed) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.width(width).height(32.dp)
            .border(1.dp, if (bad) DdfColors.Error else if (focused) DdfColors.Cyan else DdfColors.Outline, shape),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) Text("Normal", color = DdfColors.Muted.copy(alpha = 0.6f), style = TextStyle(fontSize = fontSize.sp), modifier = Modifier.padding(horizontal = 8.dp))
        BasicTextField(
            text, { text = it }, singleLine = true,
            textStyle = TextStyle(fontSize = fontSize.sp, color = DdfColors.Text), cursorBrush = SolidColor(DdfColors.Cyan),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .onFocusChanged { f -> if (focused && !f.isFocused) save(); focused = f.isFocused }
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { save(); true } else false }
                .testTag(tag),
        )
    }
}

/**
 * Coming up on the phone (1.9.9): the month picker (back, the month, forward; Custom range with its two dates), then
 * each line with its due date, the This month box and when it was last checked, and the amount.
 */
@Composable
fun ComingUpSection(
    budget: Budget, today: LocalDate, state: ComingUpState,
    onBill: (String) -> Unit, onEnvelope: (String) -> Unit, onMonthAmount: (String, LocalDate, Long?) -> Unit,
) {
    val view = comingUpView(budget, today, state)
    val items = comingUp(budget, today, view)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { state.custom = false; state.offset -= 1 }, modifier = Modifier.testTag("comingUp.back")) { Text("◀") }
        Text(comingUpLabel(view), Modifier.weight(1f).testTag("comingUp.label"), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { state.custom = false; state.offset += 1 }, modifier = Modifier.testTag("comingUp.forward")) { Text("▶") }
        TextButton(onClick = {
            if (!state.custom) {
                val w = (view as? ComingUpView.Month)?.window
                if (state.from.isBlank()) state.from = w?.start?.toString() ?: today.toString()
                if (state.to.isBlank()) state.to = w?.end?.minusDays(1)?.toString() ?: today.toString()
            }
            state.custom = !state.custom
        }, modifier = Modifier.testTag("comingUp.custom")) { Text(if (state.custom) "Month" else "Custom range") }
    }
    if (state.custom) {
        Row {
            Box(Modifier.weight(1f)) { TextFieldRow("From", state.from, { state.from = it }, Modifier.testTag("comingUp.from")) }
            HGap(8)
            Box(Modifier.weight(1f)) { TextFieldRow("To", state.to, { state.to = it }, Modifier.testTag("comingUp.to")) }
        }
    }
    Text("${Money.format(items.sumOf { it.amount })}, ${comingUpCount(view, items.size)}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("comingUp.total"))
    if (budget.bills.isEmpty()) {
        EmptyState("No bills yet. Add one, or make one from a repeating charge in Subscriptions.")
    } else if (items.isEmpty()) Text(if (view is ComingUpView.Month) "Nothing unpaid this month." else "Nothing unpaid in this range.", color = DdfColors.Muted)
    items.forEachIndexed { n, i ->
        ListRow(onClick = { i.billId?.let(onBill) ?: i.envelopeId?.let(onEnvelope) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row { Text(i.name); if (i.autopay) Pill("autopay"); if (i.billId == null) Pill("debt"); if (i.reminder) Pill("reminder") }
                    Text(
                        (if (i.due.isBefore(today)) "was due ${dayText(i.due)}, overdue" else "due ${dayText(i.due)}") +
                            (if (i.partialPaid > 0) ", ${Money.format(i.partialPaid)} paid so far" else ""),
                        color = if (i.due.isBefore(today)) DdfColors.Warning else DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    if (i.billId != null) Text(
                        i.lastChecked?.let { "Last checked ${lastCheckedText(it)}" } ?: "Not checked yet",
                        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("comingUp.checked.$n"),
                    )
                }
                if (i.billId != null && i.monthStart != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("This month", color = DdfColors.Muted, style = MaterialTheme.typography.labelSmall)
                        MonthAmountBox(i.typed, { a -> onMonthAmount(i.billId, i.monthStart, a) }, "comingUp.box.$n")
                    }
                    HGap(10)
                }
                // a column of its own, so the boxes line up whatever the amounts' widths
                androidx.compose.foundation.layout.Box(Modifier.widthIn(min = 84.dp), contentAlignment = Alignment.CenterEnd) { MoneyText(i.amount) }
            }
        }
    }
}
