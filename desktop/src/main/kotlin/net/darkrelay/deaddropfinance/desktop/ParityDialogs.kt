package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.domain.Bonus
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Money

/*
 * 0.12.3: the dialogs the program lacked next to the phone app, each doing what the app's screen does with the same
 * shared action (so a save or delete syncs the same way): a transfer or ATM withdrawal, bonus income, the budget start
 * date, and the confirmations for deleting a transaction and archiving or deleting an account.
 */

/** "Delete this transaction?" with Delete and Keep, as on the phone; with a receipt, the choice to delete it too. */
@Composable
fun DeleteTransactionRow(model: AppModel, shell: Shell, txId: String, onDone: () -> Unit, onKeep: () -> Unit) {
    var hasReceipt by remember { mutableStateOf(false) }
    var alsoReceipt by remember { mutableStateOf(false) }
    LaunchedEffect(txId) { hasReceipt = model.hasReceipt(txId) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T("Delete this transaction?", 13.5.sp, modifier = Modifier.weight(1f).testTag("delete.ask"))
        Btn("Delete", { shell.run { if (model.deleteTransaction(txId, alsoReceipt)) onDone() } }, small = true, tag = "delete.confirm")
        Btn("Keep", onKeep, ghost = true, small = true, tag = "delete.keep")
    }
    if (hasReceipt) {
        CheckRow("Delete its receipt too", "Otherwise it goes back to Receipts, waiting for a charge.", alsoReceipt, true, "delete.receipt") { alsoReceipt = it }
    }
}

/** Transfer between two of the user's own accounts; into a cash account it can take the cash from an envelope (ATM). */
@Composable
fun TransferDialog(model: AppModel, shell: Shell, b: Budget, d: Dialog.Transfer) {
    val existing = d.editId?.let { id -> b.transactions.firstOrNull { it.id == id && it.kind == TransactionKind.TRANSFER } }
    val accounts = b.accounts.filter { !it.archived || it.id == existing?.accountId || it.id == existing?.transferAccountId }
    var from by remember { mutableStateOf(existing?.accountId ?: d.fromId ?: accounts.firstOrNull()?.id ?: "") }
    var to by remember { mutableStateOf(existing?.transferAccountId ?: accounts.firstOrNull { it.id != from }?.id ?: "") }
    var amount by remember { mutableStateOf(existing?.amount?.let { Money.plain(it) } ?: "") }
    var date by remember { mutableStateOf((existing?.date ?: model.today()).toString()) }
    var memo by remember { mutableStateOf(existing?.memo ?: "") }
    var envelope by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val toCash = b.accounts.firstOrNull { it.id == to }?.type == AccountType.CASH
    val envelopes = b.fillOrder.filter { it.mode != EnvelopeMode.CASH && it.mode != EnvelopeMode.ROLLOVER }
    val atm = existing?.let { t -> b.moves.firstOrNull { it.linkedTransactionId == t.id } }
    val close = { model.dialog = null }
    Modal(if (existing == null) "Transfer" else "Edit transfer", 470.dp, close, "dialog.transfer", footer = {
        if (existing != null && !deleting) Btn("Delete", { deleting = true }, ghost = true, tag = "dialog.delete")
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Save", {
            val cents = parseAmount(amount)
            val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
            when {
                accounts.size < 2 -> model.message = "A transfer needs two accounts." to true
                day == null -> model.message = "The date must look like 2030-01-31." to true
                cents == null || cents <= 0 -> model.message = "Enter an amount above zero, like 12.34." to true
                else -> shell.run { if (model.saveTransfer(existing?.id, from, to, cents, day, memo, envelope.takeIf { toCash && existing == null })) model.dialog = null }
            }
        }, tag = "dialog.save")
    }) {
        T("Money between your own accounts: never spending or income, and no envelope changes.", 11.5.sp, C.Dim2)
        Two { m ->
            Select("From", accounts.map { it.id to it.name }, from, { from = it }, m, tag = "f.from")
            Select("To", accounts.map { it.id to it.name }, to, { to = it }, m, tag = "f.to")
        }
        Two { m -> Field("Amount", amount, { amount = it }, m, Money.plain(0), tag = "f.amount"); Field("Date", date, { date = it }, m, tag = "f.date") }
        Field("Memo", memo, { memo = it }, placeholder = "Optional", tag = "f.memo")
        if (toCash && existing == null) {
            Select<String?>("Cash from envelope (an ATM withdrawal)", listOf<Pair<String?, String>>(null to "None") + envelopes.map { it.id to it.name },
                envelope, { envelope = it }, tag = "f.atmEnvelope")
            T("The cash moves from that envelope into Cash, so spending it later comes out of Cash.", 11.5.sp, C.Dim2)
        } else if (atm != null) {
            T("An ATM withdrawal: ${money(atm.amount)} moved from ${b.envelopes.firstOrNull { it.id == atm.fromEnvelopeId }?.name ?: "an envelope"} into Cash.", 11.5.sp, C.Dim2,
                modifier = Modifier.testTag("atm.note"))
        }
        if (deleting && existing != null) DeleteTransactionRow(model, shell, existing.id, onDone = close, onKeep = { deleting = false })
    }
}

/** Bonus income: money that didn't come through the bank, to Ready to assign, tied to no account; not a payday. */
@Composable
fun BonusDialog(model: AppModel, shell: Shell, b: Budget, d: Dialog.Bonus) {
    val existing = d.editId?.let { id -> b.transactions.firstOrNull { it.id == id && it.kind == TransactionKind.BONUS } }
    var amount by remember { mutableStateOf(existing?.amount?.let { Money.plain(it) } ?: "") }
    var date by remember { mutableStateOf((existing?.date ?: model.today()).toString()) }
    var receivedAs by remember { mutableStateOf(existing?.payee?.takeIf { it in Bonus.LABELS } ?: Bonus.LABELS.first()) }
    var note by remember { mutableStateOf(existing?.memo ?: "") }
    var deleting by remember { mutableStateOf(false) }
    val close = { model.dialog = null }
    if (deleting && existing != null) {
        Modal("Delete this bonus income?", 420.dp, { deleting = false }, "dialog.deleteBonus", footer = {
            Btn("Cancel", { deleting = false }, ghost = true, tag = "dialog.cancel")
            Btn("Delete", { shell.run { if (model.deleteTransaction(existing.id)) model.dialog = null } }, tag = "dialog.confirm")
        }) { T("The amount comes back off Ready to assign.", 13.5.sp) }
        return
    }
    Modal(if (existing == null) "Bonus income" else "Edit bonus income", 470.dp, close, "dialog.bonus", footer = {
        if (existing != null) Btn("Delete", { deleting = true }, ghost = true, tag = "dialog.delete")
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Save", {
            val cents = parseAmount(amount)
            val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
            when {
                day == null -> model.message = "The date must look like 2030-01-31." to true
                cents == null || cents <= 0 -> model.message = "Enter an amount above zero, like 12.34." to true
                else -> shell.run { if (model.saveBonus(existing?.id, cents, day, receivedAs, note)) model.dialog = null }
            }
        }, tag = "dialog.save")
    }) {
        T("Money that didn't come through the bank. It goes to Ready to assign and isn't tied to any account. It isn't a payday.", 11.5.sp, C.Dim2)
        Two { m -> Field("Amount", amount, { amount = it }, m, Money.plain(0), tag = "f.amount"); Field("Date", date, { date = it }, m, tag = "f.date") }
        Select("Received as", Bonus.LABELS.map { it to it }, receivedAs, { receivedAs = it }, tag = "f.receivedAs")
        Field("Note (where it came from)", note, { note = it }, placeholder = "Optional", tag = "f.note")
    }
}

/** Budget start date: a new date, then the phone's plain warning before it applies. */
@Composable
fun BudgetStartDialog(model: AppModel, shell: Shell, b: Budget) {
    var text by remember { mutableStateOf((b.budgetStart ?: model.today()).toString()) }
    var confirm by remember { mutableStateOf<LocalDate?>(null) }
    val close = { model.dialog = null }
    val c = confirm
    if (c != null) {
        Modal("Start the budget on ${c.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US))}?", 470.dp, { confirm = null }, "dialog.confirmStart", footer = {
            Btn("Cancel", { confirm = null }, ghost = true, tag = "dialog.cancel")
            Btn("Change it", { shell.run { if (model.setBudgetStart(c)) model.dialog = null } }, tag = "dialog.confirm")
        }) {
            T("Envelope balances and Ready to assign will be recalculated from this date. Transactions before it stay in your account balances and in Categories. No transaction is changed or deleted.", 13.5.sp)
        }
        return
    }
    Modal("Budget start date", 420.dp, close, "dialog.budgetStart", footer = {
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Use this date", {
            val d = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
            if (d == null) model.message = "The date must look like 2030-01-31." to true else confirm = d
        }, tag = "dialog.save")
    }) {
        Field("New budget start date", text, { text = it }, tag = "f.date")
        T("Transactions before this date are bank history: they stay in your account balances and in Categories, but not in envelopes or Ready to assign.", 11.5.sp, C.Dim2)
    }
}

/** Archive or unarchive an account, with the phone's words for what happens to its balance. */
@Composable
fun ArchiveAccountConfirm(model: AppModel, shell: Shell, b: Budget, accountId: String, onDone: () -> Unit, onCancel: () -> Unit) {
    val a = b.accounts.firstOrNull { it.id == accountId } ?: return
    val amount = money(b.accountBalance(a.id))
    Modal(if (a.archived) "Unarchive ${a.name}?" else "Archive ${a.name}?", 470.dp, onCancel, "dialog.archiveAccount", footer = {
        Btn("Cancel", onCancel, ghost = true, tag = "dialog.cancel")
        Btn(if (a.archived) "Unarchive" else "Archive", { shell.run { if (model.setAccountArchived(a.id, !a.archived)) onDone() } }, tag = "dialog.confirm")
    }) {
        T(
            when {
                a.archived && a.trackOnly -> "It comes back to the account list. It stays track only, so its balance still doesn't count in Ready to assign."
                a.archived -> "Its balance ($amount) goes back into Ready to assign, and the account is back in the budget."
                a.trackOnly -> "It leaves the account list. Its transactions stay as history."
                else -> "This takes its balance ($amount) out of Ready to assign, as if the account was never in the budget. Its transactions stay as history."
            }, 13.5.sp,
        )
    }
}

/** Delete an account for good, naming how many transactions go with it, as on the phone. */
@Composable
fun DeleteAccountConfirm(model: AppModel, shell: Shell, b: Budget, accountId: String, onDone: () -> Unit, onCancel: () -> Unit) {
    val a = b.accounts.firstOrNull { it.id == accountId } ?: return
    var count by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(accountId) { count = model.deleteAccountCount(accountId) }
    val n = count ?: return
    Modal("Delete ${a.name}?", 470.dp, onCancel, "dialog.deleteAccount", footer = {
        Btn("Cancel", onCancel, ghost = true, tag = "dialog.cancel")
        Btn("Delete for good", { shell.run { if (model.deleteAccount(a.id)) onDone() } }, tag = "dialog.confirm")
    }) {
        T(
            "This deletes the account and its " + (if (n == 1) "1 transaction" else "$n transactions") +
                " for good. Envelope balances and Ready to assign change to match. A transfer with another account stays there as an entry to sort. This can't be undone.",
            13.5.sp,
        )
    }
}
