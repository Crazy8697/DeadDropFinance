package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.CsvImport

// ---- the envelope drawer ------------------------------------------------------------------------------------------

@Composable
fun BoxScope.EnvelopeDrawer(model: AppModel, b: Budget, id: String) {
    val e = b.envelopes.firstOrNull { it.id == id } ?: run { model.drawerEnvelope = null; return }
    val avail = b.envelopeBalance(e.id)
    val target = model.targetOf(e)
    Column(
        Modifier.align(Alignment.TopEnd).width(430.dp).fillMaxHeight().shadow(24.dp).background(C.Panel).leftLine(C.Line2).testTag("drawer"),
    ) {
        Column(Modifier.fillMaxWidth().bottomLine().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    T(e.name, 17.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("drawer.name"))
                    T(modeName(e.mode) + if (e.everyday) ", everyday spending" else "", 12.sp, C.Dim2, modifier = Modifier.padding(top = 2.dp))
                }
                Box(Modifier.clickable { model.drawerEnvelope = null }.padding(2.dp).testTag("drawer.close")) { T("✕", 17.sp, C.Dim2) }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Available", money(avail), null, Modifier.weight(1f), cyan = true, valueSize = 19.sp, tag = "drawer.available")
                StatCard("Target", target?.let { money(it) } ?: "–", null, Modifier.weight(1f), valueSize = 19.sp, tag = "drawer.target")
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp)) {
            RowHead("Activity")
            val txs = b.transactions.associateBy { it.id }
            val accounts = b.accounts.associateBy { it.id }
            val rows = b.envelopeActivity(e.id).map { a ->
                val t = a.transactionId?.let { txs[it] }
                if (t != null) {
                    val l3 = listOfNotNull(accounts[t.accountId]?.name, t.memo.takeIf { it.isNotBlank() }, "before budget start".takeIf { a.beforeBudgetStart }).joinToString(" · ")
                    ActRow(a.id, a.date, a.createdAt, a.label, e.name, l3, a.amount, cyanAmount = a.amount > 0)
                } else {
                    ActRow(a.id, a.date, a.createdAt, a.label, e.name, "Envelope to envelope", a.amount, move = true)
                }
            }
            ActivityRows(rows, "Nothing in or out yet.")
        }
        Row(Modifier.fillMaxWidth().topLine().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Add activity", { model.dialog = Dialog.AddTransaction(envelopeId = e.id) }, tag = "drawer.add")
            Btn("Move money", { model.dialog = Dialog.MoveMoney(fromId = e.id) }, ghost = true, tag = "drawer.move")
            Spacer(Modifier.weight(1f))
            Btn("Edit", { model.dialog = Dialog.EnvelopeForm(e.id) }, ghost = true, enabled = !e.isSystem, tag = "drawer.edit")
        }
    }
}

fun Modifier.leftLine(color: androidx.compose.ui.graphics.Color) =
    drawBehind { drawLine(color, androidx.compose.ui.geometry.Offset(0.5f, 0f), androidx.compose.ui.geometry.Offset(0.5f, size.height), 1.dp.toPx()) }

// ---- dialogs ------------------------------------------------------------------------------------------------------

@Composable
fun DialogHost(model: AppModel, shell: Shell, b: Budget, d: Dialog) {
    when (d) {
        is Dialog.AddTransaction -> AddTransactionDialog(model, shell, b, d)
        is Dialog.MoveMoney -> MoveMoneyDialog(model, shell, b, d)
        is Dialog.EnvelopeForm -> EnvelopeDialog(model, shell, b, d)
        is Dialog.AccountForm -> AccountDialog(model, shell, b, d)
        is Dialog.ImportCsv -> ImportDialog(model, shell, b, d)
        is Dialog.Backup -> BackupDialog(model, shell)
        is Dialog.Restore -> RestoreDialog(model, shell, d)
    }
}

@Composable
private fun Modal(title: String, width: Dp, onClose: () -> Unit, tag: String, footer: @Composable () -> Unit, body: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(C.Scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier.width(width).shadow(30.dp, shape).clip(shape).background(C.Panel).border(1.dp, C.Line2, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.testTag(tag),
        ) {
            T(title, 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().bottomLine().padding(horizontal = 20.dp, vertical = 16.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) { body() }
            Row(Modifier.fillMaxWidth().topLine().padding(horizontal = 20.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End)) { footer() }
        }
    }
}

@Composable
private fun Two(content: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) { content(Modifier.weight(1f)) }
}

/** "12.34" or "$1,234.56" -> cents; null when it isn't an amount. */
fun parseAmount(s: String): Long? = CsvImport.parseCents(s.trim().removePrefix("$").replace(",", ""), false)

private class LineState(category: String? = null, envelope: String? = null, amount: String = "") {
    var category by mutableStateOf(category)
    var envelope by mutableStateOf(envelope)
    var amount by mutableStateOf(amount)
}

/**
 * Add transaction (0.8.0): the amount as typed (positive), as in 0.7.0. Spending needs a category (the phone's rules):
 * picking one chooses the envelope that claims it (Unplanned when none does), which can still be changed. Ready to
 * assign is money in, with the Payday and Fill envelopes boxes; To sort is money in left to sort later. Split across
 * categories turns a purchase into lines, each with its category, envelope and amount, as the phone's split; split
 * across envelopes (0.9.0) does the same for money in, each line to an envelope, Ready to assign or To sort.
 */
@Composable
private fun AddTransactionDialog(model: AppModel, shell: Shell, b: Budget, d: Dialog.AddTransaction) {
    val accounts = b.accounts.filter { !it.archived }
    var date by remember { mutableStateOf(model.today().toString()) }
    var amount by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(d.accountId ?: accounts.firstOrNull()?.id ?: "") }
    val envelopes = b.fillOrder.filter { !it.isSystem }
    val envelopeIds = envelopes.map { it.id }.toSet()
    var envelope by remember { mutableStateOf(d.envelopeId ?: envelopes.firstOrNull()?.id ?: READY_TO_ASSIGN) }
    var category by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var payday by remember { mutableStateOf(d.payday) }
    var fill by remember { mutableStateOf(d.fill) }
    var split by remember { mutableStateOf(d.split) }
    // a split of money in (0.9.0): lines go to envelopes, Ready to assign or To sort, with no category
    var incomeSplit by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf(LineState(), LineState()) }
    val categories = b.categories.sortedBy { it.sortOrder }.map { it.id to it.name }
    fun claim(c: String?): String? = c?.let { b.envelopeForCategory(it) }?.takeIf { it in envelopeIds }
    val spending = envelope != READY_TO_ASSIGN && envelope != TO_SORT
    // Payday: only money in to Ready to assign; Fill envelopes: only once Payday is ticked
    val paydayAllowed = !split && envelope == READY_TO_ASSIGN
    val paydayOn = payday && paydayAllowed
    val fillOn = fill && paydayOn
    val close = { model.dialog = null }
    Modal("Add transaction", if (split) 640.dp else 470.dp, close, "dialog.add", footer = {
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Save", {
            val cents = parseAmount(amount)
            val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
            when {
                accounts.isEmpty() -> model.message = "Add an account first (Accounts, New account)." to true
                day == null -> model.message = "The date must look like 2030-01-31." to true
                cents == null || cents <= 0 -> model.message = "Enter an amount above zero, like 12.34." to true
                split -> {
                    val parsed = lines.map { l -> AppModel.SplitLine(l.category.takeIf { !incomeSplit }, l.envelope, parseAmount(l.amount) ?: -1) }
                    if (parsed.any { it.amount <= 0 }) model.message = "Give every line an amount above zero." to true
                    else shell.run { if (model.addSplit(account, day, payee, note, cents, parsed, income = incomeSplit)) model.dialog = null }
                }
                else -> shell.run { if (model.addTransaction(account, day, payee, cents, envelope, note, category.takeIf { spending }, paydayOn, fillOn)) model.dialog = null }
            }
        }, tag = "dialog.save")
    }) {
        Two { m -> Field("Date", date, { date = it }, m, tag = "f.date"); Field("Amount", amount, { amount = it }, m, "0.00", tag = "f.amount") }
        Field("Payee", payee, { payee = it }, placeholder = "Who it went to", tag = "f.payee")
        if (!split) {
            Two { m ->
                Select("Account", accounts.map { it.id to it.name }, account, { account = it }, m, tag = "f.account")
                Select(
                    "Envelope", envelopes.map { it.id to it.name } + listOf(READY_TO_ASSIGN to "Ready to assign", TO_SORT to "Not sorted yet (To sort)"),
                    envelope, { envelope = it }, m, tag = "f.envelope",
                )
            }
            if (spending) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Select("Category", categories, category, { c -> category = c; claim(c)?.let { envelope = it } }, Modifier.weight(1f), tag = "f.category", placeholder = "Choose a category")
                    Spacer(Modifier.width(13.dp))
                    Btn("Split across categories", {
                        split = true
                        incomeSplit = false
                        lines[0].category = category
                        lines[0].envelope = envelope
                    }, ghost = true, tag = "split.start")
                }
            } else {
                Btn("Split across envelopes", {
                    split = true
                    incomeSplit = true
                    lines[0].category = null
                    lines[0].envelope = envelope
                    lines[1].envelope = envelopes.firstOrNull()?.id
                }, ghost = true, tag = "split.startIncome")
            }
        } else {
            Select("Account", accounts.map { it.id to it.name }, account, { account = it }, tag = "f.account")
            val total = parseAmount(amount) ?: 0
            val used = lines.sumOf { parseAmount(it.amount) ?: 0 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Split", modifier = Modifier.weight(1f))
                T("Left to split: ${money(total - used)}", 12.sp, if (total == used) C.Cyan else C.Warn, modifier = Modifier.testTag("split.left"))
            }
            lines.forEachIndexed { i, l ->
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (incomeSplit) {
                        Select<String?>(
                            "Goes to", envelopes.map { it.id to it.name } + listOf(READY_TO_ASSIGN to "Ready to assign", TO_SORT to "Not sorted yet (To sort)"),
                            l.envelope, { l.envelope = it }, Modifier.weight(2.2f), tag = "split.$i.envelope", placeholder = "Choose where it goes",
                        )
                    } else {
                        Select("Category", categories, l.category, { c -> l.category = c; claim(c)?.let { l.envelope = it } }, Modifier.weight(1.2f), tag = "split.$i.category", placeholder = "Choose a category")
                        Select<String?>("Envelope", envelopes.map { it.id to it.name }, l.envelope, { l.envelope = it }, Modifier.weight(1f), tag = "split.$i.envelope", placeholder = "By category")
                    }
                    Field("Amount", l.amount, { l.amount = it }, Modifier.weight(0.7f), "0.00", tag = "split.$i.amount")
                    Box(Modifier.width(22.dp).padding(bottom = 9.dp)) {
                        if (lines.size > 2) Box(Modifier.clickable { lines.removeAt(i) }.testTag("split.$i.remove")) { T("✕", 13.sp, C.Dim) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Add a line", { lines.add(LineState()) }, ghost = true, small = true, tag = "split.add")
                Btn("Don't split", {
                    split = false
                    if (!incomeSplit) lines[0].category?.let { category = it }
                    lines[0].envelope?.let { envelope = it }
                }, ghost = true, small = true, tag = "split.stop")
            }
        }
        CheckRow("Payday", "The new budget month starts on this date, and leftovers sweep into Rollover. Only for money in to Ready to assign.", paydayOn, paydayAllowed, "f.payday") {
            payday = it
            if (!it) fill = false
        }
        CheckRow("Fill envelopes", "Fills your envelopes from this payday, in fill order.", fillOn, paydayOn, "f.fill", Modifier.padding(start = 26.dp)) { fill = it }
        Field("Note", note, { note = it }, placeholder = "Optional", tag = "f.note")
        T("Spending comes out of the envelope and needs a category. Ready to assign is money in. To sort is money in, left to sort later.", 11.5.sp, C.Dim2)
    }
}

@Composable
private fun MoveMoneyDialog(model: AppModel, shell: Shell, b: Budget, d: Dialog.MoveMoney) {
    val places = listOf(READY_TO_ASSIGN to "Ready to assign") + b.fillOrder.filter { it.mode != EnvelopeMode.CASH }.map { it.id to it.name }
    var from by remember { mutableStateOf(d.fromId ?: READY_TO_ASSIGN) }
    var to by remember { mutableStateOf(d.toId ?: places.firstOrNull { it.first != from }?.first ?: READY_TO_ASSIGN) }
    var amount by remember { mutableStateOf("") }
    val close = { model.dialog = null }
    Modal("Move money", 420.dp, close, "dialog.move", footer = {
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Move", {
            val cents = parseAmount(amount)
            if (cents == null || cents <= 0) model.message = "Enter an amount above zero, like 12.34." to true
            else shell.run { if (model.moveMoney(from, to, cents)) model.dialog = null }
        }, tag = "dialog.save")
    }) {
        Select("From", places, from, { from = it }, tag = "f.from")
        Select("To", places, to, { to = it }, tag = "f.to")
        Field("Amount", amount, { amount = it }, placeholder = "0.00", tag = "f.amount")
        val available = if (from == READY_TO_ASSIGN) b.readyToAssign else b.envelopeBalance(from)
        T("Available to move: ${money(available)}", 11.5.sp, C.Dim2)
    }
}

@Composable
private fun EnvelopeDialog(model: AppModel, shell: Shell, b: Budget, d: Dialog.EnvelopeForm) {
    val e = d.envelopeId?.let { id -> b.envelopes.firstOrNull { it.id == id } }
    var name by remember { mutableStateOf(e?.name ?: "") }
    var mode by remember { mutableStateOf(e?.mode ?: EnvelopeMode.FILL_TO_TARGET) }
    var amount by remember {
        mutableStateOf((when (e?.mode) { EnvelopeMode.GOAL -> e.goalAmount; EnvelopeMode.FILL_TO_TARGET, null -> e?.target; else -> e.perPayday })?.let { "%d.%02d".format(it / 100, it % 100) } ?: "")
    }
    var everyday by remember { mutableStateOf(e?.everyday ?: false) }
    val close = { model.dialog = null }
    val modes = listOf(EnvelopeMode.FILL_TO_TARGET, EnvelopeMode.ADD_EVERY_PAYDAY, EnvelopeMode.SEASONAL, EnvelopeMode.DEBT)
        .let { if (e != null && e.mode !in it) listOf(e.mode) + it else it }
    Modal(if (e == null) "New envelope" else "Edit envelope", 470.dp, close, "dialog.envelope", footer = {
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Save", {
            val cents = if (amount.isBlank()) null else parseAmount(amount)
            if (amount.isNotBlank() && (cents == null || cents < 0)) model.message = "Enter the amount like 300.00, or leave it empty." to true
            else {
                val draft = when (mode) {
                    EnvelopeMode.FILL_TO_TARGET -> BudgetActions.EnvelopeDraft(name, mode, target = cents, everyday = everyday)
                    EnvelopeMode.GOAL -> BudgetActions.EnvelopeDraft(name, mode, goalAmount = cents, goalDate = e?.goalDate, everyday = everyday)
                    else -> BudgetActions.EnvelopeDraft(name, mode, perPayday = cents, debtOwed = e?.debtOwed, everyday = everyday)
                }
                shell.run { if (model.saveEnvelope(e?.id, draft)) model.dialog = null }
            }
        }, tag = "dialog.save")
    }) {
        Field("Name", name, { name = it }, placeholder = "Groceries", tag = "f.name")
        Two { m ->
            Select("Kind", modes.map { it to modeName(it) }, mode, { mode = it }, m, tag = "f.mode")
            Field(when (mode) { EnvelopeMode.FILL_TO_TARGET -> "Target"; EnvelopeMode.GOAL -> "Goal amount"; else -> "Each payday" }, amount, { amount = it }, m, "0.00", tag = "f.amount")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Everyday spending", 13.5.sp)
                T("Counts in Safe to spend", 11.5.sp, C.Dim2)
            }
            Switch(everyday, { everyday = it }, "f.everyday")
        }
    }
}

@Composable
private fun AccountDialog(model: AppModel, shell: Shell, b: Budget, d: Dialog.AccountForm) {
    val a = d.accountId?.let { id -> b.accounts.firstOrNull { it.id == id } }
    var name by remember { mutableStateOf(a?.name ?: "") }
    var type by remember { mutableStateOf(a?.type ?: AccountType.BANK) }
    var start by remember { mutableStateOf(a?.startingBalance?.let { "%s%d.%02d".format(if (it < 0) "-" else "", kotlin.math.abs(it) / 100, kotlin.math.abs(it) % 100) } ?: "") }
    var date by remember { mutableStateOf((a?.startDate ?: model.today()).toString()) }
    var trackOnly by remember { mutableStateOf(a?.trackOnly ?: false) }
    var held by remember { mutableStateOf(a?.heldAmount?.takeIf { it > 0 }?.let { "%d.%02d".format(it / 100, it % 100) } ?: "") }
    var show by remember { mutableStateOf(a?.showInActivity ?: true) }
    val close = { model.dialog = null }
    Modal(if (a == null) "New account" else "Edit account", 470.dp, close, "dialog.account", footer = {
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Save", {
            val neg = start.trim().startsWith("-")
            val s = if (start.isBlank()) 0L else parseAmount(start.trim().removePrefix("-"))?.let { if (neg) -it else it }
            val h = if (held.isBlank()) 0L else parseAmount(held)
            val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
            when {
                s == null -> model.message = "Enter the starting balance like 1234.56." to true
                h == null -> model.message = "Enter the held amount like 100.00, or leave it empty." to true
                day == null -> model.message = "The date must look like 2030-01-31." to true
                else -> shell.run { if (model.saveAccount(a?.id, name, type, s, day, trackOnly, h, show)) model.dialog = null }
            }
        }, tag = "dialog.save")
    }) {
        Field("Name", name, { name = it }, placeholder = "Checking", tag = "f.name")
        Two { m ->
            Select("Type", AccountType.entries.map { it to accountTypeName(it) }, type, { type = it }, m, tag = "f.type")
            Field("Starting balance", start, { start = it }, m, "0.00", tag = "f.start")
        }
        Two { m ->
            Field("Balance as of", date, { date = it }, m, tag = "f.startDate")
            Field("Held amount", held, { held = it }, m, "Optional", tag = "f.held")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("Track only, not in the budget", 13.5.sp); T("The balance shows, but never counts in Ready to assign", 11.5.sp, C.Dim2) }
            Switch(trackOnly, { trackOnly = it }, "f.trackOnly")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("Show in Activity", 13.5.sp); T("Gives the account its own tab", 11.5.sp, C.Dim2) }
            Switch(show, { show = it }, "f.show")
        }
    }
}

@Composable
private fun ImportDialog(model: AppModel, shell: Shell, b: Budget, d: Dialog.ImportCsv) {
    val name = b.accounts.firstOrNull { it.id == d.accountId }?.name ?: ""
    val close = { model.dialog = null }
    Modal("Import CSV", 470.dp, close, "dialog.import", footer = {
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Import ${d.plan.toImport.size}", { shell.run { if (model.importCsv(d)) model.dialog = null } }, enabled = d.plan.toImport.isNotEmpty(), tag = "dialog.save")
    }) {
        T("${d.fileName} into $name", 13.5.sp)
        T("${d.plan.toImport.size} new transaction${if (d.plan.toImport.size == 1) "" else "s"} go to To sort.", 13.sp, C.Dim, modifier = Modifier.testTag("import.new"))
        if (d.plan.alreadyHere.isNotEmpty()) T("${d.plan.alreadyHere.size} already here, skipped.", 13.sp, C.Dim)
        if (d.plan.matchedPending.isNotEmpty()) T("${d.plan.matchedPending.size} post a pending transaction you entered.", 13.sp, C.Dim)
        if (d.plan.bad.isNotEmpty()) T("${d.plan.bad.size} row${if (d.plan.bad.size == 1) "" else "s"} can't be read, skipped.", 13.sp, C.Warn)
    }
}

// ---- backup and restore (0.8.0) -------------------------------------------------------------------------------------

/** Backup: the password twice (the phone's rule), the lost-password warning once, then where to save. */
@Composable
fun BackupDialog(model: AppModel, shell: Shell) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val problem = backupPasswordProblem(pass, again)
    val close = { model.dialog = null }
    Modal("Back up", 470.dp, close, "dialog.backup", footer = {
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Choose where to save", {
            tried = true
            if (problem == null) {
                val f = shell.pickBackupSave(net.darkrelay.deaddropfinance.backup.DesktopBackups.fileName(model.today()))
                if (f != null) {
                    val p = pass
                    shell.run { if (model.backup(f, p)) model.dialog = null }
                }
            }
        }, tag = "dialog.save")
    }) {
        T("One encrypted file with this computer's budget. It opens only with the password you choose here.", 13.sp, C.Dim)
        Field("Backup password", pass, { pass = it }, tag = "f.password", masked = true)
        Field("Password again", again, { again = it }, tag = "f.again", masked = true)
        if (tried && problem != null) T(problem, 12.sp, C.Bad, modifier = Modifier.testTag("f.problem"))
        T("There is no way to recover a lost password. Without it, the backup can't be opened, and nothing keeps a copy of it.", 11.5.sp, C.Warn, modifier = Modifier.testTag("lostPassword"))
    }
}

/** Restore: the backup's password, then a confirmation before the budget is replaced. */
@Composable
fun RestoreDialog(model: AppModel, shell: Shell, d: Dialog.Restore) {
    var pass by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(d.confirm) }
    val close = { model.dialog = null }
    if (!confirming) {
        Modal("Restore", 470.dp, close, "dialog.restore", footer = {
            Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
            Btn("Next", { if (pass.isNotEmpty()) confirming = true }, enabled = pass.isNotEmpty(), tag = "dialog.save")
        }) {
            T(d.file.name, 13.5.sp, modifier = Modifier.testTag("restore.file"))
            Field("That backup's password", pass, { pass = it }, tag = "f.password", masked = true)
        }
    } else {
        Modal("Replace this budget?", 470.dp, close, "dialog.confirmRestore", footer = {
            Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
            Btn("Replace it", {
                val p = pass
                shell.run { model.restore(d.file, p); model.dialog = null }
            }, tag = "dialog.confirm")
        }) {
            T("Everything in this computer's budget is replaced with the backup in ${d.file.name}.", 13.5.sp)
            T("A copy of the current budget is kept until the restore has been checked. A wrong password or a damaged file changes nothing.", 12.sp, C.Dim)
        }
    }
}
