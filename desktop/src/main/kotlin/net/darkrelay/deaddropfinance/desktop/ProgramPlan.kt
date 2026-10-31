package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.Bills
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Forecast
import net.darkrelay.deaddropfinance.domain.Paydays
import net.darkrelay.deaddropfinance.domain.Recurring
import net.darkrelay.deaddropfinance.ui.Nav
import net.darkrelay.deaddropfinance.ui.comingUp
import net.darkrelay.deaddropfinance.ui.dayText
import net.darkrelay.deaddropfinance.ui.ordinal

/**
 * What the phone's Home and Bills work out for the coming payday, for the program's own screens (0.14.0): the payday
 * prediction (with the user's correction), the repeating charges, the balance forecast and the payday checklist. The
 * same shared code the phone's AppRoot calls.
 */
class ProgramPlan(
    val prediction: Paydays.Prediction,
    val series: List<Recurring.Series>,
    val forecast: Forecast.Result?,
    val checklist: List<Bills.Cycle>,
    val today: LocalDate,
) {
    val nextPayday: LocalDate? get() = prediction.effectiveNext(today)

    companion object {
        suspend fun of(model: AppModel, b: Budget): ProgramPlan? = withContext(Dispatchers.IO) {
            val store = model.store() ?: return@withContext null
            val actions = model.actions ?: return@withContext null
            val today = model.today()
            val correction = BillActions(store, actions) { today }.paydayCorrection()
            val prediction = Paydays.predict(b.paydayDates, today, correction)
            val next = prediction.effectiveNext(today)
            val series = Recurring.detect(b.transactions, store.dao.subscriptionsNow())
            val forecast = next?.let { Forecast.build(b, series, today, it) }
            val checklist = Bills.checklist(b.bills, b.billPayments, b.paydayDates.lastOrNull(), next ?: today.plusDays(14))
            ProgramPlan(prediction, series, forecast, checklist, today)
        }
    }
}

/** The plan for the budget shown, worked out again whenever the budget changes. */
@Composable
fun rememberPlan(model: AppModel, b: Budget): ProgramPlan? {
    val plan by produceState<ProgramPlan?>(null, b, model.planTick) { value = ProgramPlan.of(model, b) }
    return plan
}

/** "Predicted: every 14 days", as the phone's payday line says it. */
fun paydayWhy(p: Paydays.Prediction): String = when {
    p.next == null -> "Payday is the deposit landing, never a date on the calendar."
    p.corrected -> "Set by you"
    p.guessed -> "Guessed from one payday (two weeks). Correct it if it's different."
    p.monthlyDay != null -> "Predicted: monthly, around the ${ordinal(p.monthlyDay!!)}"
    else -> "Predicted: every ${p.intervalDays} days"
}

private fun lateNote(expected: LocalDate) =
    "Payday was expected ${dayText(expected)} and hasn't landed yet. When the deposit is in, mark it as payday; the forecast counts it as tomorrow until then."

// ---- Bills, in the program's layout (0.14.0) -------------------------------------------------------------------------

/**
 * Bills in the program's own layout, the phone's Bills tab's data and actions: the next payday (Change), the autopay
 * checks, what's coming up in the next 45 days, and the repeating charges. A bill opens the phone's bill screen (Mark
 * paid, Paid in cash, edit, delete), a debt envelope its drawer; Add bill and Subscriptions open the phone's screens.
 */
@Composable
fun ProgramBillsScreen(model: AppModel) {
    val b = model.budget ?: return
    val plan = rememberPlan(model, b)
    val today = model.today()
    Body {
        val items = comingUp(b, today)
        val live = plan?.series.orEmpty().filter { !it.dismissed }
        CardRow {
            val next = plan?.prediction?.next
            StatCard("Next payday", next?.let { dayText(it) } ?: "Not yet", plan?.prediction?.let { paydayWhy(it) } ?: "", Modifier.weight(1f), hi = true, cyan = true, tag = "card.nextPayday")
            StatCard("Coming up", net.darkrelay.deaddropfinance.desktop.money(items.sumOf { it.amount }), "${items.size} unpaid in the next 45 days", Modifier.weight(1f), tag = "card.comingUp")
            StatCard("Subscriptions", "${net.darkrelay.deaddropfinance.desktop.money(Recurring.monthlyTotal(live))}/mo",
                "${live.size} repeating charge${if (live.size == 1) "" else "s"}" + live.count { it.isNew }.let { if (it > 0) ", $it new" else "" },
                Modifier.weight(1f), tag = "card.subscriptions")
        }
        if (plan?.prediction?.late == true && plan.prediction.next != null) {
            T(lateNote(plan.prediction.next!!), 12.sp, C.Dim, modifier = Modifier.padding(bottom = 14.dp).testTag("bills.late"))
        }
        val next = plan?.nextPayday
        val checks = if (next != null) Bills.autopayChecks(b, b.billPayments, today, next).groupBy { it.cycle.bill.envelopeId }.values.toList() else emptyList()
        if (checks.isNotEmpty()) {
            RowHead("Autopay checks")
            Table(listOf(Col("Envelope", 0.24f), Col("Pulls", 0.52f), Col("Has", 0.12f, true), Col("", 0.12f, true))) {
                checks.forEach { group ->
                    val w = group.first()
                    TRow(tag = "autopay.${w.envelope?.name}") {
                        Cell(0.24f) { T(w.envelope?.name ?: "The envelope", maxLines = 1) }
                        Cell(0.52f) { T(group.joinToString("; ") { "${it.cycle.bill.name} pulls ${money(it.cycle.remaining)} on ${dayText(it.cycle.due)}" }, 12.5.sp, C.Dim) }
                        Cell(0.12f, true) { T(money(w.envelopeHas), align = TextAlign.End) }
                        Cell(0.12f, true) { if (w.short > 0) Tag("short ${money(w.short)}") else Tag("covered", cyan = true) }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        RowHead("Coming up") {
            Btn("Change payday", { model.dialog = Dialog.NextPayday }, ghost = true, small = true, enabled = plan?.prediction?.next != null, tag = "bills.payday")
            Btn("Subscriptions", { model.openPhone(Nav.Subscriptions) }, ghost = true, small = true, tag = "bills.subscriptions")
            Btn("Add bill", { model.openPhone(Nav.BillEdit(null)) }, small = true, tag = "bills.add")
        }
        Table(listOf(Col("Bill", 0.40f), Col("Due", 0.24f), Col("Paid so far", 0.16f, true), Col("Amount", 0.20f, true))) {
            if (b.bills.isEmpty() && items.isEmpty()) EmptyRow("No bills yet. Add one, or make one from a repeating charge in Subscriptions.")
            else if (items.isEmpty()) EmptyRow("Nothing unpaid in the next 45 days.")
            items.forEach { i ->
                val open = { if (i.billId != null) model.openPhone(Nav.Bill(i.billId!!)) else i.envelopeId?.let { model.drawerEnvelope = it } }
                TRow(onClick = { open() }, tag = "bill.${i.name}") {
                    Cell(0.40f) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            T(i.name, maxLines = 1)
                            if (i.autopay) Tag("autopay")
                            if (i.billId == null) Tag("debt")
                            if (i.reminder) Tag("reminder", cyan = true)
                        }
                    }
                    val overdue = i.due.isBefore(today)
                    Cell(0.24f) { T(if (overdue) "was due ${dayText(i.due)}, overdue" else "due ${dayText(i.due)}", 13.sp, if (overdue) C.Warn else C.Dim) }
                    Cell(0.16f, true) { T(if (i.partialPaid > 0) money(i.partialPaid) else "", color = C.Dim, align = TextAlign.End) }
                    Cell(0.20f, true) { T(money(i.amount), align = TextAlign.End, modifier = Modifier.testTag("billAmount.${i.name}")) }
                }
            }
        }
        T("Reminders are off unless you turn them on for a bill.", 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 8.dp))
        val suggest = live.count { s -> s.subscription?.billId == null && b.bills.none { bill -> bill.matchText != null && s.keys.any { k -> net.darkrelay.deaddropfinance.domain.Payees.contains(k, bill.matchText!!) } } }
        if (suggest > 0) T("$suggest of them could be bills: open one in Subscriptions and click Make it a bill.", 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 4.dp).testTag("bills.suggest"))
    }
}

/** Change the next payday, as the phone's payday line: Set, Use the prediction (once set by hand), Cancel. */
@Composable
fun NextPaydayDialog(model: AppModel, shell: Shell, b: Budget) {
    val plan = rememberPlan(model, b)
    val p = plan?.prediction
    var text by remember(p?.next) { mutableStateOf(p?.next?.toString() ?: "") }
    val close = { model.dialog = null }
    fun save(d: LocalDate?) = shell.run {
        val store = model.store() ?: return@run
        val actions = model.actions ?: return@run
        BillActions(store, actions) { model.today() }.correctPayday(d)
        model.planTick++
        model.refresh()
        model.dialog = null
    }
    Modal("Next payday", 420.dp, close, "dialog.payday", footer = {
        if (p?.corrected == true) Btn("Use the prediction", { save(null) }, ghost = true, tag = "payday.predicted")
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Set", {
            val d = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
            if (d == null) model.message = "The date must look like 2030-01-31." to true else save(d)
        }, tag = "dialog.save")
    }) {
        Field("Next payday", text, { text = it }, placeholder = "2030-01-31", tag = "f.payday")
        T("When you expect the deposit. Payday starts when you mark the deposit as payday.", 11.5.sp, C.Dim2)
        if (p != null) T(paydayWhy(p), 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---- Categories, in the program's layout (0.14.0) ---------------------------------------------------------------------

/**
 * Categories in the program's own layout: what each category spent in the budget month picked in the top bar, the
 * phone's Categories tab's data. A row opens the category's purchases (the phone's screen); Manage categories opens
 * the phone's Manage.
 */
@Composable
fun ProgramCategoriesScreen(model: AppModel) {
    val b = model.budget ?: return
    val month = model.month
    val totals = b.categoryTotals(month)
    Body {
        CardRow {
            StatCard("Total spent", money(b.categoryTotal(month)), monthLabel(month), Modifier.weight(1f), hi = true, cyan = true, tag = "card.categoryTotal")
            StatCard("Categories", totals.count { it.first != null }.toString(), "with spending this budget month", Modifier.weight(1f), tag = "card.categoryCount")
            val top = totals.firstOrNull { it.first != null }
            StatCard("Most spent", top?.let { money(it.second) } ?: money(0), top?.first?.name ?: "Nothing yet", Modifier.weight(1f), tag = "card.categoryTop")
        }
        RowHead("Spending by category") {
            Btn("Manage categories", { model.openPhone(Nav.ManageCategories) }, ghost = true, small = true, tag = "categories.manage")
        }
        Table(listOf(Col("Category", 0.50f), Col("Claimed by", 0.30f), Col("Spent", 0.20f, true))) {
            if (totals.isEmpty()) EmptyRow("No spending in this budget month.")
            totals.forEach { (c, amount) ->
                val name = c?.name ?: Budget.UNCATEGORIZED
                TRow(onClick = { model.openPhone(Nav.CategoryPurchases(c?.id, model.monthIndex)) }, tag = "category.$name") {
                    Cell(0.50f) { T(name, color = if (c == null) C.Dim else C.Text, maxLines = 1) }
                    Cell(0.30f) { T(c?.envelopeId?.let { id -> b.envelopes.firstOrNull { it.id == id }?.name } ?: "", 13.sp, C.Dim, maxLines = 1) }
                    Cell(0.20f, true) { T(money(amount), align = TextAlign.End, modifier = Modifier.testTag("categoryAmount.$name")) }
                }
            }
        }
        if (totals.any { it.first == null }) {
            T("Uncategorized is spending from before categories. Open a purchase in Activity to give it one.", 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ---- what Home showed, on Budget (0.14.0) -----------------------------------------------------------------------------

/**
 * The parts of the phone's Home that nothing else in the program shows, on Budget in the program's style: the
 * over-assigned warning with its fix, Pay these now (the payday checklist), the balance forecast with the late payday
 * note, and the choice of envelopes Safe to Spend counts. Safe to Spend, Ready to assign, Bonus income and the
 * envelopes are already Budget's own.
 */
@Composable
fun BudgetPlanParts(model: AppModel, shell: Shell, b: Budget) {
    val plan = rememberPlan(model, b)
    if (b.readyToAssign < 0) {
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 18.dp).border(1.dp, C.Warn, shape).background(C.Warn.copy(alpha = 0.06f), shape).padding(14.dp).testTag("overAssigned"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                T("Over-assigned by ${money(-b.readyToAssign)}", 14.sp, C.Warn, FontWeight.SemiBold)
                T("More is in envelopes than you have. Move some back to Ready to assign.", 12.sp, C.Dim)
            }
            Btn("Move some back", { model.openPhone(Nav.MoveMoney(null, backToReady = true)) }, small = true, tag = "overAssigned.fix")
        }
    }
    if (plan == null) return
    val f = plan.forecast
    val late = plan.prediction.late && plan.prediction.next != null
    val list = plan.checklist
    if (f == null && !late && list.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f != null || late) Panel("Balance forecast" + (f?.let { " to payday, ${dayText(it.payday)}" } ?: ""), null, Modifier.weight(1f).testTag("forecast")) {
            if (late) T(lateNote(plan.prediction.next!!), 12.sp, C.Dim, modifier = Modifier.padding(bottom = 8.dp))
            if (f != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Stick to the plan: land at ", 13.sp, C.Dim)
                    T(money(f.plan.end), 15.sp, if (f.plan.end < 0) C.Bad else C.Cyan, FontWeight.SemiBold, modifier = Modifier.testTag("forecast.end"))
                }
                Box(Modifier.padding(vertical = 8.dp)) { net.darkrelay.deaddropfinance.ui.ForecastChart(f, 64) }
                val neg = f.usual?.firstNegative ?: f.plan.firstNegative
                T(
                    when {
                        f.plan.firstNegative != null -> "Below zero on ${dayText(f.plan.firstNegative!!.date)} even on the plan. An overdraft is likely."
                        f.usual?.firstNegative != null -> "At your usual pace it goes below zero on ${dayText(neg!!.date)}."
                        f.usual == null -> "Stays above zero on the plan."
                        else -> "Stays above zero either way."
                    },
                    12.sp, if (neg != null) C.Warn else C.Dim,
                )
                Row(Modifier.padding(top = 10.dp)) { Btn("Details", { model.openPhone(Nav.Forecast) }, ghost = true, small = true, tag = "forecast.details") }
            }
        }
        if (list.isNotEmpty()) Panel("Pay these now", "${list.count { it.isPaid }} of ${list.size} paid", Modifier.weight(1f).testTag("payNow")) {
            if (list.all { it.isPaid }) T("All ${list.size} payday bills paid", 13.sp, C.Cyan)
            else list.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        CheckRow(c.bill.name + if (c.bill.autopay) " (autopay)" else "", "due ${dayText(c.due)}" + if (c.partial) ", ${money(c.remaining)} left" else "",
                            c.isPaid, true, "payNow.${c.bill.name}") { on ->
                            shell.run {
                                val store = model.store() ?: return@run
                                val actions = model.actions ?: return@run
                                val bills = BillActions(store, actions) { model.today() }
                                if (on) bills.markPaid(c.bill.id, c.due) else bills.unmarkPaid(c.bill.id, c.due)
                                model.refresh()
                            }
                        }
                    }
                    Btn(money(if (c.isPaid) c.paid else c.remaining), { model.openPhone(Nav.Bill(c.bill.id)) }, ghost = true, small = true, tag = "payNow.open.${c.bill.name}")
                }
            }
            T("Tick a bill when you pay it. CSV import ticks them too.", 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
