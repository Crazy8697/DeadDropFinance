package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.sin
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.domain.Budget

@Composable
private fun Body(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) { content() }
}

/** Cards in a row that share the width evenly (the mockup's auto-fit grid). */
@Composable
private fun CardRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

// ---- Budget -------------------------------------------------------------------------------------------------------

@Composable
fun BudgetScreen(model: AppModel, shell: Shell) {
    val b = model.budget ?: return
    val m = model.month
    Body {
        val onBudget = b.accounts.count { it.onBudget }
        val (spent, purchases) = model.spendingIn(b)
        val funded = b.fillOrder.count { model.assignedTo(b, it) > 0 }
        CardRow {
            StatCard("Ready to assign", money(b.readyToAssign), "From $onBudget account${if (onBudget == 1) "" else "s"} in the budget", Modifier.weight(1f), hi = true, cyan = true, tag = "card.rta")
            StatCard("Safe to spend", b.safeToSpend?.let { money(it) } ?: money(0), if (b.safeToSpend == null) "No envelope is marked everyday" else "Everyday envelopes only", Modifier.weight(1f), tag = "card.safe")
            StatCard("Assigned this month", money(b.assignedIn(m)), "$funded envelope${if (funded == 1) "" else "s"} funded", Modifier.weight(1f), tag = "card.assigned")
            StatCard("Spent this month", money(spent), "$purchases transaction${if (purchases == 1) "" else "s"}", Modifier.weight(1f), tag = "card.spent")
        }
        RowHead("Envelopes") {
            Btn("Fill targets", { shell.run { model.fillTargets() } }, ghost = true, small = true, tag = "fillTargets")
            Btn("New envelope", { model.dialog = Dialog.EnvelopeForm() }, ghost = true, small = true, tag = "newEnvelope")
        }
        val rows = model.envelopesInTable(b)
        Table(
            listOf(Col("Envelope", 0.34f), Col("Target", 0.12f, true), Col("Assigned", 0.12f, true), Col("Available", 0.12f, true), Col("Progress", 0.24f), Col("", 90.dp)),
        ) {
            if (rows.isEmpty()) EmptyRow("No envelopes yet. New envelope gives your money a job.")
            rows.forEach { e -> EnvelopeRow(model, b, e) }
        }
    }
}

@Composable
private fun TableScope.EnvelopeRow(model: AppModel, b: Budget, e: EnvelopeEntity) {
    val target = model.targetOf(e)
    val avail = b.envelopeBalance(e.id)
    TRow(onClick = { model.drawerEnvelope = e.id }, tag = "env.${e.name}") {
        Cell(0.34f) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(e.name, maxLines = 1)
                if (e.everyday) Tag("everyday", modifier = Modifier.padding(start = 6.dp))
                modeTag(e)?.let { Tag(it, modifier = Modifier.padding(start = 6.dp)) }
            }
        }
        Cell(0.12f, true) { T(target?.let { money(it) } ?: "–", color = C.Dim, align = TextAlign.End) }
        Cell(0.12f, true) { T(money(model.assignedTo(b, e)), color = C.Dim, align = TextAlign.End) }
        Cell(0.12f, true) { T(money(avail), color = moneyColor(avail), align = TextAlign.End, modifier = Modifier.testTag("avail.${e.name}")) }
        Cell(0.24f) {
            val f = if (target != null && target > 0) avail.toFloat() / target else if (avail < 0) 1f else 0f
            ProgressBar(f, full = target != null && avail >= target && target > 0, over = avail < 0, modifier = Modifier.padding(top = 5.dp))
        }
        Cell(90.dp, true) { if (!e.isSystem) Btn("Assign", { model.dialog = Dialog.MoveMoney(READY_TO_ASSIGN, e.id) }, ghost = true, small = true, tag = "assign.${e.name}") }
    }
}

fun modeTag(e: EnvelopeEntity): String? = when (e.mode) {
    EnvelopeMode.FILL_TO_TARGET -> null
    EnvelopeMode.ADD_EVERY_PAYDAY -> "every payday"
    EnvelopeMode.GOAL -> "goal"
    EnvelopeMode.SEASONAL -> "seasonal"
    EnvelopeMode.DEBT -> "debt"
    EnvelopeMode.CASH -> "cash"
    EnvelopeMode.ROLLOVER -> "rollover"
}

fun modeName(m: EnvelopeMode): String = when (m) {
    EnvelopeMode.FILL_TO_TARGET -> "Fill to target"
    EnvelopeMode.ADD_EVERY_PAYDAY -> "Add every payday"
    EnvelopeMode.GOAL -> "Goal"
    EnvelopeMode.SEASONAL -> "Seasonal"
    EnvelopeMode.DEBT -> "Debt"
    EnvelopeMode.CASH -> "Cash"
    EnvelopeMode.ROLLOVER -> "Rollover"
}

// ---- a table in the mockup's style ----------------------------------------------------------------------------------

class Col(val title: String, val weight: Float = 0f, val num: Boolean = false, val width: Dp? = null) {
    constructor(title: String, width: Dp) : this(title, 0f, false, width)
}

class TableScope(val cols: List<Col>)

@Composable
fun Table(cols: List<Col>, body: @Composable TableScope.() -> Unit) {
    Frame(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().background(C.Panel2).bottomLine().padding(horizontal = 14.dp, vertical = 9.dp)) {
                cols.forEach { c ->
                    val mod = if (c.width != null) Modifier.width(c.width) else Modifier.weight(c.weight)
                    Box(mod.padding(end = if (c.num) 14.dp else 8.dp), contentAlignment = if (c.num) Alignment.CenterEnd else Alignment.CenterStart) { if (c.title.isNotEmpty()) Label(c.title) }
                }
            }
            TableScope(cols).body()
        }
    }
}

class RowCells(val scope: RowScope)

@Composable
fun TableScope.TRow(onClick: (() -> Unit)? = null, tag: String? = null, cells: @Composable RowCells.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().background(if (hovered(src) && onClick != null) C.Hover else Color.Transparent).bottomLine().hoverable(src)
            .let { if (onClick != null) it.clickable(interactionSource = src, indication = null, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 9.dp).heightIn(min = 22.dp).let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) { RowCells(this).cells() }
}

@Composable
fun RowCells.Cell(weight: Float, num: Boolean = false, content: @Composable () -> Unit) = with(scope) {
    Box(Modifier.weight(weight).padding(end = if (num) 14.dp else 8.dp), contentAlignment = if (num) Alignment.CenterEnd else Alignment.CenterStart) { content() }
}

@Composable
fun RowCells.Cell(width: Dp, num: Boolean = false, content: @Composable () -> Unit) = with(scope) {
    Box(Modifier.width(width), contentAlignment = if (num) Alignment.CenterEnd else Alignment.CenterStart) { content() }
}

@Composable
fun TableScope.EmptyRow(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) { T(text, 13.sp, C.Dim) }
}

// ---- Activity -------------------------------------------------------------------------------------------------------

/** One row of Activity: the phone's three lines (name; what it is; account and chips), the date beside them. */
class ActRow(val id: String, val date: LocalDate, val createdAt: Long, val name: String, val line2: String, val line3: String, val amount: Long?, val move: Boolean = false, val cyanAmount: Boolean = false)

private val monthAbbr = java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.US)

@Composable
fun ActivityRows(rows: List<ActRow>, empty: String, modifier: Modifier = Modifier) {
    Frame(modifier.fillMaxWidth()) {
        Column {
            if (rows.isEmpty()) Box(Modifier.padding(14.dp)) { T(empty, 13.sp, C.Dim) }
            rows.forEach { r -> ActivityRowView(r) }
        }
    }
}

@Composable
private fun ActivityRowView(r: ActRow) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().background(if (hovered(src)) C.Hover else Color.Transparent).bottomLine().hoverable(src)
            .padding(horizontal = 14.dp, vertical = 10.dp).testTag("act.${r.name}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.width(62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            T("${r.date.dayOfMonth}", 15.sp, C.Dim, FontWeight.SemiBold)
            T(r.date.format(monthAbbr).uppercase(), 11.sp, C.Dim2)
        }
        Column(Modifier.weight(1f)) {
            T(r.name, 14.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(r.line2, 12.sp, C.Dim, maxLines = 1)
                if (r.move) Tag("move", cyan = true, modifier = Modifier.padding(start = 6.dp))
            }
            T(r.line3, 11.sp, C.Dim2, maxLines = 1)
        }
        T(
            r.amount?.let { money(it) } ?: "",
            15.sp, r.amount?.let { if (r.cyanAmount) C.Cyan else moneyColor(it) } ?: C.Dim2, align = TextAlign.End,
            modifier = Modifier.testTag("actAmount.${r.name}"),
        )
    }
}

/** A transaction as the phone's Activity row shows it ([forAccount]: on that account's tab). */
fun txRow(t: TransactionEntity, b: Budget, forAccount: String? = null): ActRow {
    val accounts = b.accounts.associateBy { it.id }
    val envelopes = b.envelopes.associateBy { it.id }
    val incoming = t.kind == TransactionKind.TRANSFER && forAccount != null && t.transferAccountId == forAccount
    val sub = when {
        incoming -> "Transfer from ${accounts[t.accountId]?.name ?: "?"}"
        t.kind == TransactionKind.TRANSFER -> "Transfer to ${accounts[t.transferAccountId]?.name ?: "?"}"
        t.kind == TransactionKind.BONUS -> t.memo.ifBlank { "Ready to assign" }
        t.isWash -> "Wash (pass-through)"
        b.beforeAccountStart(t) -> "Before the starting balance"
        else -> {
            val ls = b.lines.filter { it.transactionId == t.id }
            if (ls.size > 1) "Split, ${ls.size} parts" else when (ls.firstOrNull()?.bucket) {
                LineBucket.ENVELOPE -> envelopes[ls.first().envelopeId]?.name ?: ""
                LineBucket.READY_TO_ASSIGN -> "Ready to assign"
                LineBucket.TRANSFER -> "Transfer between your accounts"
                LineBucket.OFF_BUDGET -> "Outside the budget"
                else -> "To sort"
            }.let { if (t.returnOf != null) "Return, back to $it" else if (t.sortedBy == SortedBy.AUTO) "$it, by a rule" else it }
        }
    }
    val chips = buildList {
        if (t.kind == TransactionKind.NORMAL && !t.isWash && (t.amount < 0 || t.returnOf != null)) {
            val names = b.lines.filter { it.transactionId == t.id && (it.bucket == LineBucket.ENVELOPE || it.bucket == LineBucket.UNSORTED) }
                .map { b.categoryName(it.categoryId) }.distinct()
            if (names.isNotEmpty()) add(names.joinToString(", "))
        }
        if (t.kind == TransactionKind.BONUS) add(t.payee)
        if (t.isPayday) add("payday")
        if (t.pending) add("pending")
        if (t.fee) add("fee")
        if (t.feeRebate) add("fee rebate")
        if (b.beforeBudgetStart(t)) add("before budget start")
    }
    val account = if (t.kind == TransactionKind.BONUS) "Bonus income" else accounts[t.accountId]?.name ?: ""
    val line3 = (listOf(account) + chips + listOfNotNull(t.memo.takeIf { it.isNotBlank() && t.kind != TransactionKind.BONUS })).filter { it.isNotBlank() }.joinToString(" · ")
    return ActRow(
        t.id, t.date, t.createdAt,
        if (t.kind == TransactionKind.BONUS) "Bonus income" else t.payee.ifBlank { if (t.kind == TransactionKind.TRANSFER) "Transfer" else "No description" },
        sub, line3,
        if (incoming) t.amount else if (t.kind == TransactionKind.TRANSFER) -t.amount else t.amount,
        cyanAmount = t.amount > 0 && t.kind != TransactionKind.TRANSFER,
    )
}

/** A move between envelopes (or Ready to Assign) as a labeled row. */
fun moveRows(b: Budget, month: Budget.Month): List<ActRow> {
    val names = b.envelopes.associate { it.id to it.name }
    fun place(id: String?) = if (id == null) "Ready to assign" else names[id] ?: "a deleted envelope"
    val moves = b.moves.filter { month.contains(it.date) }
    // a payday's auto-fill is one row, as in the mockup: how many envelopes, and the total
    val fills = moves.filter { it.kind == net.darkrelay.deaddropfinance.data.MoveKind.AUTOFILL }.groupBy { it.date }.map { (date, ms) ->
        ActRow("fill.$date", date, ms.maxOf { it.createdAt }, "Payday auto-fill", "${ms.size} envelope${if (ms.size == 1) "" else "s"}", "Envelope to envelope", ms.sumOf { it.amount }, move = true)
    }
    return fills + moves.filter { it.kind != net.darkrelay.deaddropfinance.data.MoveKind.AUTOFILL }.map { m ->
        val label = when (m.kind) {
            net.darkrelay.deaddropfinance.data.MoveKind.SWEEP -> "Rollover sweep"
            net.darkrelay.deaddropfinance.data.MoveKind.COVER -> "Covered from Rollover"
            net.darkrelay.deaddropfinance.data.MoveKind.ATM -> "Cash from an ATM"
            else -> "Moved from ${place(m.fromEnvelopeId)}"
        }
        ActRow(m.id, m.date, m.createdAt, label, place(m.toEnvelopeId), "Envelope to envelope", m.amount, move = true, cyanAmount = false)
    }
}

@Composable
fun ActivityScreen(model: AppModel, shell: Shell) {
    val b = model.budget ?: return
    val tabs = model.activityTabs(b)
    if (model.activityTab != null && tabs.none { it.id == model.activityTab }) model.activityTab = null
    val selected = tabs.firstOrNull { it.id == model.activityTab }
    Body {
        Row(Modifier.fillMaxWidth().bottomLine().padding(bottom = 0.dp)) {
            TabButton("All", selected == null, "tab.All") { model.activityTab = null }
            tabs.forEach { a -> TabButton(a.name, selected?.id == a.id, "tab.${a.name}") { model.activityTab = a.id } }
        }
        Spacer(Modifier.height(14.dp))
        val all = selected == null
        RowHead("Transactions") {
            Btn("Import CSV", {
                val id = selected?.id ?: return@Btn
                val f = shell.pickCsv() ?: return@Btn
                shell.run { model.planImport(id, f.name, f.readText()) }
            }, ghost = true, small = true, enabled = !all, tag = "importCsv")
            Btn("Add", { model.dialog = Dialog.AddTransaction(selected?.id) }, small = true, enabled = !all, tag = "add")
        }
        val m = model.month
        if (selected == null) {
            val rows = (b.transactions.filter { m.contains(it.date) }.map { txRow(it, b) } + moveRows(b, m))
                .sortedWith(compareByDescending<ActRow> { it.date }.thenByDescending { it.createdAt })
            ActivityRows(rows, "Nothing in this budget month yet.")
            T("The All tab is view only. Add and Import CSV live on an account tab.", 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 10.dp).testTag("viewOnly"))
        } else {
            val txs = b.transactions.filter { (it.accountId == selected.id || it.transferAccountId == selected.id) && m.contains(it.date) }
                .sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.createdAt })
            val rows = txs.map { txRow(it, b, selected.id) } +
                if (m.contains(selected.startDate)) listOf(ActRow("start", selected.startDate, 0, "Starting balance", "Balance as of ${selected.startDate}", selected.name, selected.startingBalance)) else emptyList()
            T("Current balance ${money(b.accountBalance(selected.id))}", 12.5.sp, C.Dim, modifier = Modifier.padding(bottom = 10.dp).testTag("tabBalance"))
            ActivityRows(rows, "Nothing on this account in this budget month.")
        }
    }
}

@Composable
private fun TabButton(text: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).hoverable(src).clickable(interactionSource = src, indication = null, onClick = onClick).testTag(tag)) {
        T(text, 13.sp, if (on) C.Cyan else if (hovered(src)) C.Text else C.Dim, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        Box(Modifier.height(2.dp).fillMaxWidth().background(if (on) C.Cyan else Color.Transparent))
    }
}

// ---- Accounts -------------------------------------------------------------------------------------------------------

fun accountTypeName(t: AccountType) = when (t) {
    AccountType.BANK -> "Bank account"
    AccountType.PAYMENT_APP -> "Payment app"
    AccountType.CASH -> "Cash"
}

@Composable
fun AccountsScreen(model: AppModel, shell: Shell) {
    val b = model.budget ?: return
    Body {
        RowHead("Accounts") { Btn("New account", { model.dialog = Dialog.AccountForm() }, small = true, tag = "newAccount") }
        val list = b.accounts.sortedWith(compareBy({ it.archived }, { it.sortOrder }, { it.name }))
        Table(listOf(Col("Account", 0.30f), Col("Type", 0.18f), Col("Flags", 0.32f), Col("Balance", 0.20f, true), Col("", 90.dp))) {
            if (list.isEmpty()) EmptyRow("No accounts yet. New account to get started.")
            list.forEach { a ->
                val bal = b.accountBalance(a.id)
                TRow(tag = "acct.${a.name}") {
                    Cell(0.30f) { T(a.name, maxLines = 1) }
                    Cell(0.18f) { T(accountTypeName(a.type), color = C.Dim) }
                    Cell(0.32f) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            when {
                                a.archived -> Tag("Archived")
                                a.trackOnly -> Tag("Track only")
                                else -> Tag("In budget", cyan = true)
                            }
                            if (!a.showInActivity) Tag("Hidden from Activity", cyan = true)
                            if (a.heldAmount > 0 && a.onBudget) Tag("Holds ${money(a.heldAmount)}", cyan = true)
                        }
                    }
                    Cell(0.20f, true) { T(money(bal), color = moneyColor(bal), align = TextAlign.End, modifier = Modifier.testTag("bal.${a.name}")) }
                    Cell(90.dp, true) { Btn("Edit", { model.dialog = Dialog.AccountForm(a.id) }, ghost = true, small = true, tag = "editAccount.${a.name}") }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        val inBudget = b.accounts.filter { it.onBudget }.sumOf { b.accountBalance(it.id) }
        val tracked = b.accounts.filter { !it.archived && it.trackOnly }.sumOf { b.accountBalance(it.id) }
        CardRow {
            StatCard("In the budget", money(inBudget), "Counts toward Ready to assign", Modifier.weight(1f), tag = "card.inBudget")
            StatCard("Tracked only", money(tracked), "Does not count toward the budget", Modifier.weight(1f), tag = "card.tracked")
            StatCard("Net", money(inBudget + tracked), "All accounts", Modifier.weight(1f), tag = "card.net")
        }
    }
}

// ---- Reports (Phase 5 placeholders) ---------------------------------------------------------------------------------

private val reports = listOf(
    "Spending by envelope" to "Where the month actually went",
    "Spending by category" to "Rolled up across envelopes",
    "Income vs expense" to "Month over month",
    "Trends" to "A line per envelope over time",
    "Money flow" to "Income into envelopes into spending",
    "Bills calendar" to "What is due and when",
    "Net worth" to "All accounts over time",
    "Debt paydown" to "Interest, plan and payoff date",
    "Payday forecast" to "What the next payday covers",
    "Savings goals" to "Progress against each target",
    "Merchant pages" to "Totals, trips and most bought",
    "Price history" to "What an item cost over time",
)

@Composable
fun ReportsScreen() {
    Body {
        RowHead("Reports") { Tag("Phase 5", cyan = true) }
        T("Nothing here is built yet. These tiles mark where each report would sit once the charts phase starts.", 12.5.sp, C.Dim, modifier = Modifier.padding(bottom = 16.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = maxOf(1, ((maxWidth + 14.dp) / (270.dp + 14.dp)).toInt())
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                reports.chunked(perRow).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        chunk.forEach { (title, sub) -> PlaceholderTile(title, sub, Modifier.weight(1f)) }
                        repeat(perRow - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderTile(title: String, sub: String, modifier: Modifier) {
    Frame(modifier.height(168.dp), dashed = true) {
        Column(Modifier.fillMaxSize().padding(16.dp).testTag("report.$title")) {
            T(title, 13.5.sp, weight = FontWeight.SemiBold)
            T(sub, 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp).alpha(0.35f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until 9) {
                    val pct = (18 + (sin(i * 1.7) * 30 + 40).roundToInt()).coerceIn(0, 100) / 100f
                    Box(Modifier.weight(1f).fillMaxHeight(pct).background(Brush.verticalGradient(listOf(C.Cyan, C.Teal))))
                }
            }
        }
    }
}

// ---- Sync (0.10.0) -------------------------------------------------------------------------------------------------

@Composable
fun SyncScreen(model: AppModel, server: ServerModel?) {
    Body {
        Frame(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).testTag("syncSummary")) {
                if (server == null || !server.connected) {
                    T("Not connected", 14.sp, weight = FontWeight.SemiBold)
                    T("Log in under Settings > Server to keep this computer's budget in step with your phone through your own Dead Drop server.",
                        12.5.sp, C.Dim, modifier = Modifier.padding(top = 6.dp))
                } else {
                    val s = server.status
                    T(ServerModel.pillText(s), 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("sync.state"))
                    Kv("Server", server.sync.serverUrl ?: "")
                    Kv("Last synced", if (s.lastSyncedAt > 0) ServerModel.clockTime(s.lastSyncedAt) else "Not yet")
                    Kv("Changes waiting", s.queued.toString())
                    Kv("Editing", when {
                        s.readOnlyBy != null -> "${s.readOnlyBy} (this computer is read only)"
                        else -> "This computer, while its window is open"
                    }, last = true)
                    s.message?.let { T(it, 12.sp, C.Bad, modifier = Modifier.padding(top = 6.dp)) }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (server != null && server.connected) Btn("Sync now", { server.syncNow() }, small = true, tag = "sync.now")
                    Btn("Server settings", { model.go(Screen.SETTINGS) }, ghost = true, small = true, tag = "sync.settings")
                }
            }
        }
    }
}

// ---- Settings -------------------------------------------------------------------------------------------------------

@Composable
internal fun Panel(title: String, hint: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Frame(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            T(title, 14.sp, weight = FontWeight.SemiBold)
            if (hint != null) T(hint, 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 3.dp, bottom = 14.dp)) else Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, 13.5.sp)
            T(sub, 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(on, onChange, tag)
    }
}

@Composable
internal fun Kv(key: String, value: String, last: Boolean = false, tag: String? = null) {
    Row(Modifier.fillMaxWidth().let { if (last) it else it.bottomLine() }.padding(vertical = 8.dp)) {
        T(key, 13.sp, C.Dim, modifier = Modifier.weight(1f))
        T(value, 13.sp, weight = FontWeight.Bold, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

private val startFormat = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)

@Composable
fun SettingsScreen(model: AppModel, shell: Shell) {
    Body {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("General", "Stored in your user folder. Nothing is written next to the program.", Modifier.weight(1f)) {
                ToggleRow("Check for updates at startup", "Silent when you are already current", shell.checkAtStartup, "setting.checkUpdates", shell.onCheckAtStartup)
                shell.hello?.let { h ->
                    ToggleRow("Windows Hello unlock", "Asks for Windows Hello each time the program opens. Off by default.", h.enabled, "setting.hello") { on ->
                        val hwnd = shell.windowHandle()
                        shell.run { h.set(on, hwnd) }
                    }
                    h.message?.let { T(it, 12.sp, C.Dim, modifier = Modifier.padding(top = 4.dp).testTag("hello.message")) }
                }
            }
            Panel("Budget", "The phone app's budget settings, for this computer's budget.", Modifier.weight(1f)) {
                val start = model.budget?.budgetStart
                Kv("Budget start date", start?.format(startFormat) ?: "Not set", last = true, tag = "budgetStart")
                T("Transactions before this date are bank history: they stay in account balances, but not in envelopes or Ready to assign.", 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Panel("Backup and restore", "One encrypted file with this computer's budget, opened with its recovery key. The same format as the phone's backups.", Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Back up...", { model.startBackup() }, ghost = true, small = true, enabled = model.budget != null, tag = "settings.backup")
                Btn("Restore...", { if (model.canRestore()) shell.pickBackupOpen()?.let { model.dialog = Dialog.Restore(it) } }, ghost = true, small = true, enabled = model.budget != null, tag = "settings.restore")
            }
        }
        Spacer(Modifier.height(16.dp))
        ServerPanel(model, model.server, shell)
        Spacer(Modifier.height(16.dp))
        Panel("About", null, Modifier.fillMaxWidth()) {
            Kv("Version", BuildInfo.VERSION, tag = "aboutVersion")
            Kv("Data folder", shell.dataFolder)
            Kv("Publisher", "darkrelay.net", last = true)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Check for updates", shell.onCheckNow, ghost = true, small = true, tag = "about.check")
                Btn("Open data folder", { shell.onOpenDataFolder?.invoke() }, ghost = true, small = true, enabled = shell.onOpenDataFolder != null)
                Btn("Licenses", { shell.onLicenses?.invoke() }, ghost = true, small = true, enabled = shell.onLicenses != null)
            }
        }
    }
}
