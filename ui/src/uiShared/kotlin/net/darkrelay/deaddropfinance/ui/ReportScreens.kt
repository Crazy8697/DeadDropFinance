package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Forecast
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.Reports
import net.darkrelay.deaddropfinance.domain.Reports.Kind

/**
 * Reports (stage 27: phone 1.9.0, program 0.15.0): twelve tiles, each opening its report. One set of screens for the
 * phone (More > Reports) and the program (its Reports screen); the numbers are Reports'. Reports about a budget month
 * follow the month stepper: the phone's own Older and Newer here, the program's in its top bar.
 */
class ReportData(
    val budget: Budget,
    val today: LocalDate,
    val receipts: List<ReceiptEntity>,
    val items: List<ReceiptItemEntity>,
    /** The Budget screen's forecast to the next payday; null without a payday to forecast to. */
    val forecast: Forecast.Result?,
) {
    val months: List<Budget.Month> = budget.months(today)
    fun month(i: Int): Budget.Month = months[i.coerceIn(0, months.size - 1)]
}

/** 1.9.1: items come only from scanned receipts, so with none there is nothing to list. */
const val NEEDS_RECEIPTS = "No items yet. Items come from scanned receipts: scan a receipt and its lines show here."

private val dayFormat = DateTimeFormatter.ofPattern("MMM d")
private val fullDay = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun day(d: LocalDate) = d.format(dayFormat)

/** Older and Newer through the budget months (the phone); the program steps them in its top bar instead (null). */
@Composable
private fun MonthRow(d: ReportData, index: Int, onMonth: ((Int) -> Unit)?) {
    val i = index.coerceIn(0, d.months.size - 1)
    // the program: its top bar names and steps the month
    if (onMonth == null) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onMonth(i + 1) }, enabled = i < d.months.size - 1) { Text("‹ Older") }
        Text(monthLabel(d.month(i), d.today), Modifier.weight(1f).testTag("report.month"), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onMonth(i - 1) }, enabled = i > 0) { Text("Newer ›") }
    }
}

@Composable
private fun Small(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = DdfColors.Muted) =
    Text(text, modifier, color = color, style = MaterialTheme.typography.bodySmall)

@Composable
private fun KeyValue(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = DdfColors.Muted)
        value()
    }
}

@Composable
private fun Box(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Column(Modifier.padding(12.dp), content = content) }
}

// ---- the grid -----------------------------------------------------------------------------------------------------

@Composable
fun ReportsScreen(d: ReportData, monthIndex: Int, onMonth: ((Int) -> Unit)?, onBack: (() -> Unit)?, onOpen: (Kind) -> Unit) {
    val m = d.month(monthIndex)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("reports")) {
        ScreenTitle("Reports", onBack)
        MonthRow(d, monthIndex, onMonth)
        Gap()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = maxOf(1, (maxWidth / 270.dp).toInt()).coerceAtMost(4)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Kind.entries.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { k -> ReportTile(k, d, m, Modifier.weight(1f)) { onOpen(k) } }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Gap(24)
    }
}

/** A report's tile: its name, its line and its small chart (the grid's, and the phone Home's cards, 1.9.1). */
@Composable
fun ReportTile(k: Kind, d: ReportData, m: Budget.Month, modifier: Modifier, onOpen: () -> Unit) {
    Card(modifier.height(176.dp).clickable(onClick = onOpen).testTag("report.${k.name}")) {
        Column(Modifier.padding(12.dp)) {
            Text(k.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Small(k.sub, Modifier.padding(bottom = 6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) { TileBody(k, d, m) }
        }
    }
}

/** A tile's one line and its small chart. */
@Composable
private fun TileBody(k: Kind, d: ReportData, m: Budget.Month) {
    val b = d.budget
    when (k) {
        Kind.SPENDING_BY_ENVELOPE, Kind.SPENDING_BY_CATEGORY -> {
            val s = if (k == Kind.SPENDING_BY_ENVELOPE) Reports.spendingByEnvelope(b, m) else Reports.spendingByCategory(b, m)
            MiniBars(s.take(8).map { it.amount })
            Small(s.firstOrNull()?.let { "${Money.format(s.sumOf { x -> x.amount })} spent. Most: ${it.name}" } ?: "No spending in this budget month.")
        }
        Kind.INCOME_VS_EXPENSE -> {
            val r = Reports.incomeVsExpense(d.budget, d.today, m, 6)
            MiniBars(r.flatMap { listOf(it.income, it.spent) })
            val now = r.last()
            Small(if (now.income == 0L && now.spent == 0L) "No income or spending yet." else "In ${Money.format(now.income)}, out ${Money.format(now.spent)}")
        }
        Kind.TRENDS -> {
            val months = Reports.monthsEndingAt(b, d.today, m, 6)
            val top = Reports.topEnvelopes(b, months, 1)
            val t = Reports.trends(b, months, top).firstOrNull()
            MiniLine(t?.spent ?: emptyList())
            Small(t?.let { "${it.envelope.name}, the last ${months.size} budget months" } ?: "No spending in an envelope yet.")
        }
        Kind.MONEY_FLOW -> {
            val f = Reports.moneyFlow(b, m)
            MiniBars(listOf(f.total, f.envelopes.sumOf { it.given }, f.spent), DdfColors.Teal)
            Small(if (f.isEmpty) "Nothing came in or was given out yet." else "${Money.format(f.income)} in, ${Money.format(f.spent)} spent of what was given")
        }
        Kind.BILLS_CALENDAR -> {
            val due = Reports.billsCalendar(b, YearMonth.from(d.today))
            val next = due.firstOrNull { !it.paid && !it.date.isBefore(d.today) }
            MiniBars((1..d.today.lengthOfMonth()).map { day -> due.filter { it.date.dayOfMonth == day }.sumOf { it.amount } })
            Small(next?.let { "Next: ${it.name}, ${day(it.date)}" } ?: if (due.isEmpty()) "Nothing due this month." else "Everything this month is paid.")
        }
        Kind.NET_WORTH -> {
            val w = Reports.netWorth(b, d.today, Reports.monthsEndingAt(b, d.today, m, 6))
            MiniLine(w.map { it.total })
            Small(if (b.accounts.isEmpty()) "No accounts yet." else "Now ${Money.format(w.last().total)}")
        }
        Kind.DEBT_PAYDOWN -> {
            val p = Reports.debtPlans(b, d.today)
            MiniBars(p.mapNotNull { it.owed }, DdfColors.Warning)
            Small(if (p.isEmpty()) "No Debt envelopes." else "${Money.format(p.sumOf { it.owed ?: 0 })} owed" + (p.mapNotNull { it.payoff }.maxOrNull()?.let { ", paid off by ${it.format(fullDay)}" } ?: ""))
        }
        Kind.PAYDAY_FORECAST -> {
            val f = d.forecast
            MiniLine(f?.plan?.days?.map { it.balance } ?: emptyList())
            Small(f?.let { "Payday ${day(it.payday)}: land at ${Money.format(it.plan.end)}" } ?: "Mark a payday to see what the next one covers.")
        }
        Kind.SAVINGS_GOALS -> {
            val g = Reports.savingsGoals(b, d.today)
            MiniBars(g.map { it.percent ?: 0 }, DdfColors.Teal)
            Small(if (g.isEmpty()) "No Goal envelopes yet." else "${g.count { it.reached }} of ${g.size} reached")
        }
        Kind.MERCHANTS -> {
            val list = Reports.merchants(b, d.receipts, d.items, m)
            MiniBars(list.take(8).map { it.total })
            Small(list.firstOrNull()?.let { "Most: ${it.name}, ${it.trips} trip${if (it.trips == 1) "" else "s"}" } ?: "No purchases in this budget month.")
        }
        Kind.PRICE_HISTORY -> {
            val items = Reports.priceItems(b, d.receipts, d.items)
            val top = items.firstOrNull()
            MiniLine(top?.let { Reports.priceHistory(b, d.receipts, d.items, it.key).map { p -> p.unitPrice } } ?: emptyList())
            Small(top?.let { "${it.name}, bought ${it.times} time${if (it.times == 1) "" else "s"}" } ?: "Needs scanned receipts.")
        }
    }
}

// ---- each report ----------------------------------------------------------------------------------------------------

/** One report's full view. */
@Composable
fun ReportScreen(
    k: Kind,
    d: ReportData,
    monthIndex: Int,
    onMonth: ((Int) -> Unit)?,
    onBack: () -> Unit,
    onMerchant: (String) -> Unit,
    onItem: (String) -> Unit,
    onForecast: () -> Unit,
    onEnvelope: (String) -> Unit,
) {
    val m = d.month(monthIndex)
    val b = d.budget
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("reportView.${k.name}")) {
        ScreenTitle(k.title, onBack)
        when (k) {
            Kind.SPENDING_BY_ENVELOPE, Kind.SPENDING_BY_CATEGORY -> {
                MonthRow(d, monthIndex, onMonth)
                val s = if (k == Kind.SPENDING_BY_ENVELOPE) Reports.spendingByEnvelope(b, m) else Reports.spendingByCategory(b, m)
                KeyValue("Total spent") { MoneyText(s.sumOf { it.amount }, bold = true) }
                if (s.isEmpty()) EmptyState("No spending in this budget month.")
                ShareBars(s, onClick = if (k == Kind.SPENDING_BY_ENVELOPE) { x -> x.id?.let(onEnvelope) } else null)
                Small(if (k == Kind.SPENDING_BY_ENVELOPE) "Purchases less returns, from each envelope. To sort is spending not given an envelope yet." else "Purchases less returns, by what they were, whichever envelope paid. History before the budget start counts, as on Categories.", Modifier.padding(top = 8.dp))
            }
            Kind.INCOME_VS_EXPENSE -> {
                MonthRow(d, monthIndex, onMonth)
                val r = Reports.incomeVsExpense(b, d.today, m)
                if (r.all { it.income == 0L && it.spent == 0L }) EmptyState("No income or spending yet.")
                else {
                    PairBars(r.map { monthShort(it.month) }, r.map { it.income }, r.map { it.spent }, DdfColors.Cyan, DdfColors.Warning, 160)
                    Legend(listOf(DdfColors.Cyan to "Income", DdfColors.Warning to "Spent"))
                }
                r.reversed().forEach { t ->
                    ListRow(null) {
                        Text(monthLabel(t.month, d.today), style = MaterialTheme.typography.titleSmall)
                        KeyValue("Income") { MoneyText(t.income) }
                        KeyValue("Spent") { MoneyText(t.spent) }
                        KeyValue("Net") { MoneyText(t.net, bold = true) }
                        t.savingsRate?.let { KeyValue("Kept of income") { Text(percentText(it)) } }
                    }
                }
            }
            Kind.TRENDS -> {
                MonthRow(d, monthIndex, onMonth)
                val months = Reports.monthsEndingAt(b, d.today, m)
                var chosen by remember { mutableStateOf<List<String>?>(null) }
                val ids = chosen ?: Reports.topEnvelopes(b, months)
                val t = Reports.trends(b, months, ids)
                if (t.isEmpty()) EmptyState("No spending in an envelope yet. Pick envelopes below to follow them.")
                else {
                    LineChart(t.mapIndexed { i, x -> seriesColor(i) to x.spent }, months.map { monthShort(it) }, 180)
                    Legend(t.mapIndexed { i, x -> seriesColor(i) to "${x.envelope.name}: ${Money.format(x.spent.sum())} in all" })
                }
                SectionTitle("Envelopes to show")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.fillOrder.forEach { e ->
                        FilterChip(
                            selected = e.id in ids,
                            onClick = { chosen = if (e.id in ids) ids - e.id else ids + e.id },
                            label = { Text(e.name) },
                            modifier = Modifier.testTag("trend.${e.name}"),
                        )
                    }
                }
            }
            Kind.MONEY_FLOW -> {
                MonthRow(d, monthIndex, onMonth)
                val f = Reports.moneyFlow(b, m)
                if (f.isEmpty) EmptyState("Nothing came in or was given to an envelope in this budget month.")
                else {
                    FlowChart(f, 320)
                    Box {
                        KeyValue("Came in") { MoneyText(f.income) }
                        if (f.earlier > 0) KeyValue("Given from money already there") { MoneyText(f.earlier) }
                        KeyValue("Given to envelopes") { MoneyText(f.envelopes.sumOf { it.given }) }
                        KeyValue("Spent of that") { MoneyText(f.spent) }
                        KeyValue("Kept in the envelopes") { MoneyText(f.kept) }
                        if (f.notAssigned > 0) KeyValue("Not assigned yet") { MoneyText(f.notAssigned) }
                    }
                    if (f.spentBeyond > 0) Small("Also spent this month: ${Money.format(f.spentBeyond)} from money the envelopes held before it. Spending still in To sort isn't in the picture.")
                    f.envelopes.forEach { e ->
                        ListRow(e.id?.let { id -> { onEnvelope(id) } }) {
                            Text(e.name)
                            KeyValue("Given") { MoneyText(e.given) }
                            KeyValue("Spent") { MoneyText(e.spent) }
                        }
                    }
                }
            }
            Kind.BILLS_CALENDAR -> {
                var ym by remember { mutableStateOf(YearMonth.from(d.today)) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { ym = ym.minusMonths(1) }) { Text("‹ Earlier") }
                    Text(ym.format(monthFormat), Modifier.weight(1f).testTag("calendar.month"), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { ym = ym.plusMonths(1) }) { Text("Later ›") }
                }
                val due = Reports.billsCalendar(b, ym)
                CalendarGrid(ym, due, d.today)
                if (due.isEmpty()) EmptyState("No bills or debt payments are due this month.")
                KeyValue("Due this month") { MoneyText(due.sumOf { it.amount }, bold = true) }
                KeyValue("Still to pay") { MoneyText(due.filter { !it.paid }.sumOf { it.amount }) }
                due.forEach { x ->
                    ListRow(null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(x.name)
                                Small(day(x.date) + listOfNotNull(if (x.debt) "debt payment" else null, if (x.autopay) "autopay" else null, if (x.paid) "paid" else null).joinToString("") { ", $it" })
                            }
                            MoneyText(x.amount, color = if (x.paid) DdfColors.Muted else null)
                        }
                    }
                }
                Small("Bills and Debt envelopes with a required payment and due date. Paid ones are greyed.", Modifier.padding(top = 8.dp))
            }
            Kind.NET_WORTH -> {
                MonthRow(d, monthIndex, onMonth)
                if (b.accounts.isEmpty()) EmptyState("Add an account to see your net worth.")
                else {
                    val months = Reports.monthsEndingAt(b, d.today, m)
                    val w = Reports.netWorth(b, d.today, months)
                    LineChart(listOf(DdfColors.Cyan to w.map { it.total }), months.map { monthShort(it) }, 170)
                    val last = w.last()
                    KeyValue("Net worth, ${last.date.format(fullDay)}") { MoneyText(last.total, bold = true) }
                    w.getOrNull(w.size - 2)?.let { prev -> KeyValue("Change since ${prev.date.format(fullDay)}") { MoneyText(last.total - prev.total) } }
                    SectionTitle("Every account")
                    b.accounts.sortedBy { it.sortOrder }.forEach { a ->
                        ListRow(null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.name)
                                    Small(listOfNotNull(if (a.trackOnly) "track only" else null, if (a.archived) "archived" else null).joinToString(", ").ifEmpty { "in the budget" })
                                }
                                MoneyText(last.balances[a.id] ?: 0)
                            }
                        }
                    }
                    Small("Each account's balance at the end of each budget month, pending ones included. Bonus income isn't in an account, so it isn't here.", Modifier.padding(top = 8.dp))
                }
            }
            Kind.DEBT_PAYDOWN -> {
                val plans = Reports.debtPlans(b, d.today)
                if (plans.isEmpty()) EmptyState("No Debt envelopes. Make one, with what's owed and its interest rate, to plan paying it down.")
                plans.forEach { p ->
                    Box {
                        Text(p.envelope.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { onEnvelope(p.envelope.id) })
                        KeyValue("Still owed") { p.owed?.let { MoneyText(it) } ?: Text("Not entered", color = DdfColors.Muted) }
                        KeyValue(if (p.perPayday) "Paid each payday" else "Paid each month") { p.payment?.let { MoneyText(it) } ?: Text("Not set", color = DdfColors.Muted) }
                        KeyValue("Interest rate") { Text(p.rateHundredths?.let { Reports.rateText(it) + " a year" } ?: "Not entered", color = if (p.rateHundredths == null) DdfColors.Muted else DdfColors.Text) }
                        p.payoff?.let { KeyValue("Paid off") { Text(it.format(fullDay), color = DdfColors.Cyan, modifier = Modifier.testTag("debt.payoff.${p.envelope.name}")) } }
                        if (p.payoff != null && p.payments > 0) {
                            KeyValue("Payments left") { Text("${p.payments}") }
                            KeyValue("Interest on the way") { MoneyText(p.interest) }
                        }
                        p.whyNot?.let { Small(it, color = DdfColors.Warning) }
                        if (p.rateHundredths == null && p.payoff != null && p.payments > 0) Small("No interest rate entered, so this leaves interest out. Add the rate on the envelope's Edit screen.")
                        if (p.balances.size > 1) LineChart(listOf(DdfColors.Warning to p.balances.map { it.second }), listOf(day(p.balances.first().first), p.balances.last().first.format(fullDay)).let { ends ->
                            List(p.balances.size) { i -> if (i == 0) ends[0] else if (i == p.balances.lastIndex) ends[1] else "" }
                        }, 120, tag = "chart.debt.${p.envelope.name}")
                    }
                }
                Small("Interest is the yearly rate charged by the day on what's left, before each payment.", Modifier.padding(top = 8.dp))
            }
            Kind.PAYDAY_FORECAST -> {
                val f = d.forecast
                if (f == null) EmptyState("Mark a payday to see what the next one covers.")
                else {
                    val c = Reports.paydayCover(f)
                    Small("Today to the day before payday (${day(c.payday)}), budget accounts only. The same forecast as the Budget screen's.")
                    ForecastChart(f, 160)
                    Box {
                        KeyValue("Balance today") { MoneyText(c.start) }
                        KeyValue("Bills, autopays and debt payments") { MoneyText(-c.bills) }
                        KeyValue("Everyday spending on the plan") { MoneyText(-c.spending) }
                        KeyValue("Lowest point" + (c.lowestOn?.let { ", ${day(it)}" } ?: "")) { MoneyText(c.lowest) }
                        KeyValue("Day before payday") { MoneyText(c.end, bold = true) }
                    }
                    Small(if (c.end >= 0) "The money there now covers everything until payday, on the plan." else "On the plan the money runs out before payday.", color = if (c.end >= 0) DdfColors.Muted else DdfColors.Warning)
                    TextButton(onClick = onForecast, modifier = Modifier.testTag("report.forecastDetails")) { Text("Day by day") }
                }
            }
            Kind.SAVINGS_GOALS -> {
                val g = Reports.savingsGoals(b, d.today)
                if (g.isEmpty()) EmptyState("No Goal envelopes yet. Make one with an amount and a date.")
                g.forEach { x ->
                    ListRow({ onEnvelope(x.envelope.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(x.envelope.name, Modifier.weight(1f))
                            Text(x.percent?.let { percentText(it) } ?: "No amount", color = if (x.reached) DdfColors.Cyan else DdfColors.Muted)
                        }
                        Bar((x.percent ?: 0) / 10000f, if (x.reached) DdfColors.Cyan else DdfColors.Teal)
                        Row {
                            MoneyText(x.saved, small = true)
                            Small(" of " + (x.target?.let { Money.format(it) } ?: "no amount") + (x.by?.let { " by ${it.format(fullDay)}" } ?: ""))
                        }
                        if (!x.reached && x.perPayday > 0) Small("${Money.format(x.perPayday)} each payday reaches it on time.")
                        if (x.reached) Small("Reached.", color = DdfColors.Cyan)
                    }
                }
            }
            Kind.MERCHANTS -> {
                var allTime by remember { mutableStateOf(false) }
                if (!allTime) MonthRow(d, monthIndex, onMonth)
                Row {
                    FilterChip(selected = !allTime, onClick = { allTime = false }, label = { Text("This budget month") })
                    HGap()
                    FilterChip(selected = allTime, onClick = { allTime = true }, label = { Text("All time") }, modifier = Modifier.testTag("merchants.allTime"))
                }
                val list = Reports.merchants(b, d.receipts, d.items, if (allTime) null else m)
                if (list.isEmpty()) EmptyState(if (allTime) "No purchases yet." else "No purchases in this budget month.")
                list.forEach { x ->
                    ListRow({ onMerchant(x.key) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("merchant.${x.name}")) {
                            Column(Modifier.weight(1f)) {
                                Text(x.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Small("${x.trips} trip${if (x.trips == 1) "" else "s"}" + (x.items.firstOrNull()?.let { ", most bought: ${it.name}" } ?: ""))
                            }
                            MoneyText(x.total)
                        }
                    }
                }
            }
            Kind.PRICE_HISTORY -> {
                var q by remember { mutableStateOf("") }
                OutlinedTextField(q, { q = it }, label = { Text("Find an item") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("price.search"))
                val items = Reports.priceItems(b, d.receipts, d.items).filter { q.isBlank() || it.key.contains(Reports.itemKey(q)) }
                if (items.isEmpty()) EmptyState(if (q.isBlank()) NEEDS_RECEIPTS else "No item matches that.")
                items.take(200).forEach { x ->
                    ListRow({ onItem(x.key) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("item.${x.key}")) {
                            Text(x.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Small("bought ${x.times} time${if (x.times == 1) "" else "s"}")
                        }
                    }
                }
            }
        }
        Gap(24)
    }
}

/** One payee's page: totals, trips, what was bought most, and its categories. */
@Composable
fun MerchantScreen(d: ReportData, key: String, monthIndex: Int, onBack: () -> Unit, onItem: (String) -> Unit) {
    val all = Reports.merchants(d.budget, d.receipts, d.items, null).firstOrNull { it.key == key }
    val month = Reports.merchants(d.budget, d.receipts, d.items, d.month(monthIndex)).firstOrNull { it.key == key }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("merchantPage")) {
        ScreenTitle(all?.name ?: "Merchant", onBack)
        if (all == null) { EmptyState("Nothing from this payee any more."); return@Column }
        Box {
            KeyValue("Spent, all time") { MoneyText(all.total, bold = true) }
            KeyValue("Trips") { Text("${all.trips}") }
            KeyValue("Each trip, on average") { MoneyText(all.average) }
            all.last?.let { KeyValue("Last time") { Text(it.format(fullDay)) } }
            KeyValue("This budget month") { MoneyText(month?.total ?: 0) }
        }
        SectionTitle("Most bought")
        if (all.items.isEmpty()) Small(NEEDS_RECEIPTS)
        all.items.take(30).forEach { i ->
            ListRow({ onItem(i.key) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(i.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Small("${i.times} time${if (i.times == 1) "" else "s"}" + if (i.quantityMilli != i.times * 1000L) ", ${qty(i.quantityMilli)} in all" else "")
                    }
                    MoneyText(i.spent)
                }
            }
        }
        if (all.byCategory.isNotEmpty()) {
            SectionTitle("By category")
            ShareBars(all.byCategory)
        }
        Gap(24)
    }
}

private fun qty(milli: Long): String = if (milli % 1000 == 0L) "${milli / 1000}" else "%.3f".format(milli / 1000.0).trimEnd('0').trimEnd('.')

/** What one item cost each time, over time. */
@Composable
fun PriceItemScreen(d: ReportData, key: String, onBack: () -> Unit) {
    val h = Reports.priceHistory(d.budget, d.receipts, d.items, key)
    val name = Reports.priceItems(d.budget, d.receipts, d.items).firstOrNull { it.key == key }?.name ?: key
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("pricePage")) {
        ScreenTitle(name, onBack)
        if (h.isEmpty()) { EmptyState("No prices for this item."); return@Column }
        LineChart(listOf(DdfColors.Cyan to h.map { it.unitPrice }), h.map { day(it.date) }, 160)
        Box {
            KeyValue("Lowest") { MoneyText(h.minOf { it.unitPrice }) }
            KeyValue("Highest") { MoneyText(h.maxOf { it.unitPrice }) }
            KeyValue("Last, ${h.last().date.format(fullDay)}") { MoneyText(h.last().unitPrice, bold = true) }
            if (h.size > 1) KeyValue("Change since the first time") { MoneyText(h.last().unitPrice - h.first().unitPrice) }
        }
        h.reversed().forEach { p ->
            ListRow(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.date.format(fullDay))
                        Small(p.store.ifBlank { "No store" } + if (p.quantityMilli != 1000L) ", ${qty(p.quantityMilli)} bought" else "")
                    }
                    MoneyText(p.unitPrice)
                }
            }
        }
        Small("The price of one: a scanned receipt line's unit price, or its amount over the quantity.", Modifier.padding(vertical = 8.dp))
        Gap(24)
    }
}
