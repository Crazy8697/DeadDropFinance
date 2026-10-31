package net.darkrelay.deaddropfinance.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetRuleException
import net.darkrelay.deaddropfinance.domain.CsvImport

/** The sidebar. Home, Bills, Categories and More (0.13.0) are the phone app's own screens (PhoneScreens). */
enum class Screen(val title: String) {
    BUDGET("Budget"), ACTIVITY("Activity"), ACCOUNTS("Accounts"), RECEIPTS("Receipts"), BILLS("Bills"), CATEGORIES("Categories"),
    REPORTS("Reports"), SYNC("Sync"), SETTINGS("Settings"),
}

/** An open dialog: Add transaction, Move money, an envelope or account form, or a CSV import's confirmation. */
sealed class Dialog {
    /** [payday], [fill], [split]: how the dialog starts (Payday ticked, split lines), as when picked by hand. */
    data class AddTransaction(val accountId: String? = null, val envelopeId: String? = null, val payday: Boolean = false, val fill: Boolean = false, val split: Boolean = false,
                              /** 0.12.3: an existing transaction, opened from its row to edit (and delete). */
                              val editId: String? = null) : Dialog()
    /** A transfer between the user's own accounts, or an ATM withdrawal (0.12.3); [editId] edits one. */
    data class Transfer(val editId: String? = null, val fromId: String? = null) : Dialog()
    /** Bonus income (0.12.3): money in outside the bank, to Ready to assign; [editId] edits one. */
    data class Bonus(val editId: String? = null) : Dialog()
    /** Budget start date (0.12.3): change it, after a plain warning. */
    object BudgetStart : Dialog()
    /** Bills (0.14.0): the next payday, set by hand or back to the prediction. */
    object NextPayday : Dialog()
    /** [editId] (0.14.0): a move made by hand, opened from its row to edit (and delete). */
    data class MoveMoney(val fromId: String? = null, val toId: String? = null, val editId: String? = null) : Dialog()
    data class EnvelopeForm(val envelopeId: String? = null) : Dialog()
    data class AccountForm(val accountId: String? = null) : Dialog()
    data class ImportCsv(val accountId: String, val fileName: String, val plan: CsvImport.Plan) : Dialog()
    /** Backup: where to save; encrypted with the recovery key (0.11.0). */
    object Backup : Dialog()
    /** Restore from [file]: its recovery key (or an older backup's password), then a confirmation before the budget is replaced. */
    data class Restore(val file: java.io.File, val confirm: Boolean = false) : Dialog()
    /** The recovery key, shown once (0.11.0), then [next]. */
    data class ShowRecoveryKey(val next: Dialog? = null) : Dialog()
    /** Settings > Currency (0.12.0): the common currencies, then every ISO 4217 one, searchable. */
    object CurrencyPicker : Dialog()

    /** The server's dialogs (0.10.0). */
    sealed class ServerDialog : Dialog()
    data class ConfirmLogin(val username: String, val pass: String, val name: String, val stay: Boolean = true) : ServerDialog() {
        override fun toString() = "ConfirmLogin"
    }
    data class TakeOver(val holder: String) : ServerDialog()
    data class RemoveDevice(val id: String, val name: String) : ServerDialog()
    object Logout : ServerDialog()
    object AddPhone : ServerDialog()
    /** Password change (0.11.0): a new account password, typed twice. */
    object ChangePassword : ServerDialog()
}

/** A backup password has to be at least this long (as on the phone). */
const val BACKUP_PASSWORD_MIN = 8

/** Why a new backup password can't be used, or null when it can (the phone's rule). */
fun backupPasswordProblem(passphrase: String, again: String): String? = when {
    passphrase.length < BACKUP_PASSWORD_MIN -> "Use at least $BACKUP_PASSWORD_MIN characters."
    passphrase != again -> "The two passwords don't match."
    else -> null
}

/** Where a Move money end points: Ready to Assign (null) or an envelope. */
const val READY_TO_ASSIGN = "__rta__"

/** Add transaction's "Not sorted yet (To sort)": money in, left to sort later. */
const val TO_SORT = "__tosort__"

/**
 * The desktop program's screen state and the actions behind its buttons, on the program's own budget through the
 * shared engine. Every write goes through [BudgetActions] (the phone's own rules); a refused one comes back as a
 * plain message in [message]. Writes run on the caller's thread: the window calls them off the UI thread.
 */
/** Where a restore stages a backup's receipt photos, in the data folder, before swapping them in (0.14.0). */
const val RESTORE_STAGING = "receipts-restore"

class AppModel(val engine: Engine?, private val today: () -> LocalDate = LocalDate::now) {

    var budget by mutableStateOf<Budget?>(null)
        private set
    var screen by mutableStateOf(Screen.BUDGET)
    /** Index into [months] (0 = the open budget month). */
    var monthIndex by mutableStateOf(0)
    /** Activity's tab: null = All. */
    var activityTab by mutableStateOf<String?>(null)
    var drawerEnvelope by mutableStateOf<String?>(null)
    var dialog by mutableStateOf<Dialog?>(null)
    /** A plain line under the top bar: a refused action's reason, or what an action did. */
    var message by mutableStateOf<Pair<String, Boolean>?>(null)
    /** The server connection (0.10.0), once the budget is open. */
    var server by mutableStateOf<ServerModel?>(null)
    /** The budget's currency (0.12.0), an ISO 4217 code; amounts redraw when it changes. */
    var currency by mutableStateOf(net.darkrelay.deaddropfinance.domain.Currencies.DEFAULT)
        private set

    internal fun store(): net.darkrelay.deaddropfinance.data.BudgetStore? = (engine?.opened as? DesktopDatabase.Opened.Ok)?.store

    /** Settings > Currency: display only, no amount changes; synced to the account's other devices as the budget start is. */
    suspend fun setCurrency(code: String): Boolean {
        val st = store() ?: return false
        return try {
            st.setCurrency(code)
            refresh()
            message = "Amounts now show in $code. No amount was changed." to false
            true
        } catch (e: net.darkrelay.deaddropfinance.data.ReadOnlyException) {
            message = (e.message ?: "Read only.") to true
            false
        } catch (e: IllegalArgumentException) {
            message = (e.message ?: "That isn't a currency code.") to true
            false
        }
    }

    /** The phone's rules: a purchase entered by hand needs a category (0.8.0). */
    val actions: BudgetActions? = engine?.actions?.also { it.requireCategories = true }
    val unavailable: String? = (engine?.opened as? DesktopDatabase.Opened.Unavailable)?.reason
        ?: if (engine == null) "the budget is still opening" else null

    fun today(): LocalDate = today.invoke()

    val months: List<Budget.Month> get() = budget?.months(today()) ?: listOf(Budget.Month(null, null))
    val month: Budget.Month get() = months.getOrElse(monthIndex) { months.first() }

    fun go(s: Screen) {
        screen = s
        drawerEnvelope = null
        phoneNav = null
    }

    /** 0.13.0: one of the phone app's screens, opened from a program screen (To sort, Details, Fill order); Back returns. */
    var phoneNav by mutableStateOf<net.darkrelay.deaddropfinance.ui.Nav?>(null)
    /** Rises when the phone's screens must start over (after Purge data, which then opens the setup wizard). */
    var phoneGeneration by mutableStateOf(0)
    /** Rises when the payday plan (Bills, Budget's forecast) must be worked out again: the payday was changed. */
    var planTick by mutableStateOf(0)

    fun openPhone(n: net.darkrelay.deaddropfinance.ui.Nav) {
        dialog = null
        drawerEnvelope = null
        phoneNav = n
    }

    /** Reads the budget again (after every write). */
    suspend fun refresh() {
        // the currency may have come from a restore or another device (0.12.0)
        currency = store()?.applyCurrency() ?: currency
        budget = actions?.snapshot()
        if (monthIndex >= months.size) monthIndex = 0
    }

    private suspend fun act(done: String? = null, block: suspend BudgetActions.() -> Unit): Boolean {
        val a = actions ?: return false
        return try {
            a.block()
            refresh()
            message = done?.let { it to false }
            true
        } catch (e: BudgetRuleException) {
            message = (e.message ?: "That can't be done.") to true
            false
        } catch (e: Exception) {
            message = "That didn't work: ${e.javaClass.simpleName}." to true
            false
        }
    }

    // ---- actions --------------------------------------------------------------------------------------------

    /** Move money: from and to are envelope ids, or [READY_TO_ASSIGN]. Out of Ready to Assign is an Assign. */
    suspend fun moveMoney(from: String, to: String, amount: Long): Boolean {
        if (amount <= 0) { message = "Enter an amount above zero." to true; return false }
        if (from == to) { message = "Pick two different places." to true; return false }
        return act {
            if (from == READY_TO_ASSIGN) assign(to, amount)
            else move(from, to.takeIf { it != READY_TO_ASSIGN }, amount, MoveKind.MOVE, today())
        }
    }

    /**
     * Add transaction: [amount] as typed (positive), as in 0.7.0. Into Ready to Assign it's money in; into an envelope
     * it's spending from that envelope, with a [category] (required, as on the phone); To sort ([TO_SORT]) is money in
     * left to sort later, as the phone's Income "Not sorted yet". [payday] (money in to Ready to Assign only) starts the
     * new budget month on [date]; [fill] then runs the payday fill.
     */
    suspend fun addTransaction(
        accountId: String, date: LocalDate, payee: String, amount: Long, envelope: String?, note: String,
        category: String? = null, payday: Boolean = false, fill: Boolean = false,
        /** 0.12.3: [id] edits that transaction; [pending] as the phone's box; [moneyIn] puts money straight into the envelope. */
        id: String? = null, pending: Boolean = false, moneyIn: Boolean? = null,
    ): Boolean {
        if (amount <= 0) { message = "Enter an amount above zero." to true; return false }
        // money in unless said otherwise only for Ready to assign and To sort, as before 0.12.3
        val incoming = moneyIn ?: (envelope == READY_TO_ASSIGN || envelope == TO_SORT)
        if (!incoming && envelope == READY_TO_ASSIGN) { message = "Ready to assign is only for money in." to true; return false }
        val line = when {
            envelope == READY_TO_ASSIGN -> BudgetActions.LineDraft(null, amount, toReadyToAssign = true)
            envelope == TO_SORT -> BudgetActions.LineDraft(null, if (incoming) amount else -amount, categoryId = category.takeIf { !incoming })
            incoming -> BudgetActions.LineDraft(envelope, amount)
            else -> BudgetActions.LineDraft(envelope, -amount, categoryId = category)
        }
        val signed = if (incoming) amount else -amount
        val draft = BudgetActions.TransactionDraft(accountId, date, payee.trim(), note.trim(), signed, listOf(line), pending = pending)
        if (payday && envelope != READY_TO_ASSIGN) { message = "Only money in to Ready to assign can be a payday." to true; return false }
        // an existing deposit to Ready to assign: the Payday box marks or unmarks it, as the phone's edit does
        if (id != null && envelope == READY_TO_ASSIGN) {
            var plan: net.darkrelay.deaddropfinance.domain.Payday.Plan? = null
            val ok = act { plan = saveDeposit(id, draft, payday, fill, today()) }
            if (ok) message = (plan?.let { paydayText(it, fill, date) } ?: "Saved. It isn't a payday now.") to false
            return ok
        }
        if (!payday) return act("Saved.") { saveTransaction(id, draft) }
        var plan: net.darkrelay.deaddropfinance.domain.Payday.Plan? = null
        val ok = act { plan = savePayday(draft, fill, today()).second }
        val p = plan
        if (ok && p != null) message = paydayText(p, fill, date) to false
        return ok
    }

    private fun paydayText(p: net.darkrelay.deaddropfinance.domain.Payday.Plan, fill: Boolean, date: LocalDate): String {
        val names = budget?.envelopes?.associate { it.id to it.name }.orEmpty()
        return when {
            !fill -> "Payday: the new budget month starts $date." + if (p.sweeps.isEmpty()) "" else " ${p.sweeps.size} swept into Rollover."
            p.shortfalls.isEmpty() -> "Payday: ${p.fills.size} envelope${if (p.fills.size == 1) "" else "s"} filled, ${p.sweeps.size} swept into Rollover."
            else -> "Payday: ${p.fills.size} envelope${if (p.fills.size == 1) "" else "s"} filled, ${p.sweeps.size} swept into Rollover. Short: " +
                p.shortfalls.joinToString(", ") { "${names[it.envelopeId] ?: "an envelope"} ${money(it.short)}" } + "."
        }
    }

    /** One line of a split purchase: its category (required), its envelope (the category's by default) and amount. */
    data class SplitLine(val category: String?, val envelope: String?, val amount: Long)

    /**
     * A purchase split into lines (one trip: groceries, electronics, fishing), as the phone's split. [income] (0.9.0):
     * money in split across envelopes, Ready to assign (READY_TO_ASSIGN) and To sort (TO_SORT), as the phone's.
     */
    suspend fun addSplit(accountId: String, date: LocalDate, payee: String, note: String, total: Long, lines: List<SplitLine>, income: Boolean = false,
                         id: String? = null, pending: Boolean = false): Boolean {
        if (total <= 0) { message = "Enter an amount above zero." to true; return false }
        if (lines.size < 2) { message = "A split needs at least two lines." to true; return false }
        if (income) {
            if (lines.any { it.envelope == null }) { message = "Choose where every line goes." to true; return false }
            val drafts = lines.map {
                when (it.envelope) {
                    READY_TO_ASSIGN -> BudgetActions.LineDraft(null, it.amount, toReadyToAssign = true)
                    TO_SORT -> BudgetActions.LineDraft(null, it.amount)
                    else -> BudgetActions.LineDraft(it.envelope, it.amount)
                }
            }
            return act("Saved.") { saveTransaction(id, BudgetActions.TransactionDraft(accountId, date, payee.trim(), note.trim(), total, drafts, pending = pending)) }
        }
        val drafts = lines.map { BudgetActions.LineDraft(it.envelope, -it.amount, categoryId = it.category) }
        return act("Saved.") { saveTransaction(id, BudgetActions.TransactionDraft(accountId, date, payee.trim(), note.trim(), -total, drafts, pending = pending)) }
    }

    // ---- 0.12.3: the program does what the app does ---------------------------------------------------------

    /** A row's transaction, opened in its edit form: a transfer's, a bonus's, or the purchase and deposit form. */
    /** An Activity or envelope row: a move made by hand opens Edit move (0.14.0), a transaction its own dialog. */
    fun openRow(r: ActRow) { if (r.moveId != null) dialog = Dialog.MoveMoney(editId = r.moveId) else if (r.tx) openTransaction(r.id) }

    /** Edit a move (0.14.0), the shared rule the phone uses: [from] and [to] are envelope ids or [READY_TO_ASSIGN]. */
    suspend fun editMove(id: String, from: String, to: String, amount: Long, date: LocalDate): Boolean {
        if (amount <= 0) { message = "Enter an amount above zero." to true; return false }
        if (from == to) { message = "Pick two different places." to true; return false }
        return act("Saved.") { editMove(id, from.takeIf { it != READY_TO_ASSIGN }, to.takeIf { it != READY_TO_ASSIGN }, amount, date) }
    }

    /** Delete a move made by hand (0.14.0): a soft delete, synced, as a transaction's. */
    suspend fun deleteMove(id: String): Boolean = act("Deleted.") { deleteMove(id) }

    fun openTransaction(id: String) {
        val t = budget?.transactions?.firstOrNull { it.id == id } ?: return
        dialog = when (t.kind) {
            TransactionKind.TRANSFER -> Dialog.Transfer(editId = id)
            TransactionKind.BONUS -> Dialog.Bonus(editId = id)
            else -> Dialog.AddTransaction(editId = id)
        }
    }

    /** Whether [txId] has a receipt (from the phone): the delete then offers to delete it too. */
    suspend fun hasReceipt(txId: String): Boolean = store()?.dao?.receiptsNow()?.any { it.transactionId == txId } == true

    /** Delete, as the phone's: a soft delete (tombstones, synced like any change); its receipt goes back to waiting, or with [alsoReceipt] goes too. */
    suspend fun deleteTransaction(id: String, alsoReceipt: Boolean = false): Boolean = act("Deleted.") { deleteTransaction(id, deleteReceipt = alsoReceipt) }

    /** A transfer between two of the user's accounts; into a cash account with [fromEnvelope], an ATM withdrawal. */
    suspend fun saveTransfer(id: String?, from: String, to: String, amount: Long, date: LocalDate, memo: String, fromEnvelope: String?): Boolean =
        act("Saved.") { saveTransfer(id, from, to, amount, date, memo, fromEnvelope) }

    suspend fun saveBonus(id: String?, amount: Long, date: LocalDate, receivedAs: String, note: String): Boolean =
        act("Saved.") { saveBonus(id, amount, date, receivedAs, note) }

    suspend fun setAccountArchived(id: String, archived: Boolean): Boolean =
        act(if (archived) "Archived." else "Back in the account list.") { setAccountArchived(id, archived) }

    suspend fun deleteAccountCount(id: String): Int = actions?.deleteAccountCount(id) ?: 0

    suspend fun deleteAccount(id: String): Boolean = act("Deleted.") { deleteAccount(id) }

    suspend fun setEnvelopeArchived(id: String, archived: Boolean): Boolean {
        val ok = act(if (archived) "Archived." else "Back in the budget.") { setEnvelopeArchived(id, archived) }
        if (ok && archived) drawerEnvelope = null
        return ok
    }

    suspend fun coverFromRollover(id: String): Boolean {
        var amount = 0L
        val ok = act { amount = coverFromRollover(id) }
        if (ok) message = "Covered ${money(amount)} from Rollover." to false
        return ok
    }

    /** Changes the budget start date, then labels the history before it, as the phone does. No transaction changes. */
    suspend fun setBudgetStart(d: LocalDate): Boolean {
        val st = store() ?: return false
        return act("The budget starts ${d}.") {
            st.setBudgetStart(d)
            runCatching { net.darkrelay.deaddropfinance.domain.Sorting(st, this).labelHistory() }
        }
    }

    /** Fill targets: each envelope in fill order gets what its mode wants (the phone's Assign Add), while money lasts. */
    suspend fun fillTargets(): Boolean {
        var total = 0L
        var count = 0
        val ok = act {
            for (e in snapshot().fillOrder.filter { !it.isSystem }) {
                val given = addToEnvelope(e.id, today())
                if (given > 0) { total += given; count++ }
            }
        }
        if (ok) message = (if (count == 0) "Nothing to fill: the envelopes are full or nothing is ready to assign." else "Filled $count envelope${if (count == 1) "" else "s"}, ${money(total)}.") to false
        return ok
    }

    suspend fun saveEnvelope(id: String?, draft: BudgetActions.EnvelopeDraft): Boolean = act("Saved.") { saveEnvelope(id, draft, today()) }

    suspend fun saveAccount(id: String?, name: String, type: AccountType, start: Long, startDate: LocalDate, trackOnly: Boolean, held: Long, showInActivity: Boolean): Boolean =
        act("Saved.") {
            saveAccount(id, name, type, start, startDate, trackOnly, held, showInActivity)
            // 0.12.3: a budget made on this computer gets its start date with its first account, as the phone's setup gives it one
            store()?.ensureBudgetStart(today(), setupDone = false)
        }

    /** Reads a bank's CSV export and plans its import into [accountId]; opens the confirmation. */
    suspend fun planImport(accountId: String, fileName: String, text: String): Boolean {
        val b = budget ?: return false
        return try {
            val layout = CsvImport.detect(text)
            val read = CsvImport.read(text, layout)
            if (read.rows.isEmpty()) throw BudgetRuleException("No transactions found in $fileName.")
            dialog = Dialog.ImportCsv(accountId, fileName, CsvImport.plan(read, accountId, b.transactions))
            true
        } catch (e: BudgetRuleException) {
            message = (e.message ?: "That file can't be read.") to true
            false
        } catch (e: Exception) {
            message = "$fileName can't be read as a bank's CSV export." to true
            false
        }
    }

    suspend fun importCsv(d: Dialog.ImportCsv): Boolean {
        var result: BudgetActions.ImportResult? = null
        val ok = act {
            val r = importCsv(d.accountId, d.plan)
            result = r
            // 0.12.3: what runs on new transactions after an import, as on the phone (history labels, bill payments, receipts)
            store()?.let { st -> net.darkrelay.deaddropfinance.domain.Arrivals(st, this, { today() }).landed(r.newIds, autoApprove = false) }
        }
        result?.let { r -> message = "Imported ${r.imported} transaction${if (r.imported == 1) "" else "s"} into To sort; ${r.skipped} already here, ${r.bad} unreadable." to false }
        return ok
    }

    // ---- what the screens show ----------------------------------------------------------------------------

    // ---- backup and restore (0.8.0) ------------------------------------------------------------------------

    private fun backups(): net.darkrelay.deaddropfinance.backup.DesktopBackups? {
        val ok = engine?.opened as? DesktopDatabase.Opened.Ok ?: return null
        return net.darkrelay.deaddropfinance.backup.DesktopBackups(ok.store.db, ok.file, "desktop-${BuildInfo.VERSION}",
            photos = receiptImages, stagingDir = java.io.File(ok.file.absoluteFile.parentFile, RESTORE_STAGING))
    }

    /** The receipt photos (0.14.0), encrypted in the data folder: backups carry them, as the phone's do. */
    var receiptImages: net.darkrelay.deaddropfinance.data.ReceiptImages? = null

    private fun photosLine(n: Int) = if (n == 0) "" else ", $n receipt photo${if (n == 1) "" else "s"}"

    /** This computer's recovery keys (0.11.0), DPAPI-protected in the data folder. */
    var recoveryKeys: net.darkrelay.deaddropfinance.backup.RecoveryKeys? = null

    /** Back up...: the recovery key first when there's none yet (or it wasn't confirmed as saved), then where to save. */
    fun startBackup() {
        val keys = recoveryKeys ?: return
        keys.ensure()
        dialog = if (keys.toShow() != null) Dialog.ShowRecoveryKey(Dialog.Backup) else Dialog.Backup
    }

    /** A key made here and not yet confirmed as saved is shown (again) before anything else. */
    fun showRecoveryKeyIfNew(next: Dialog? = null) {
        if (recoveryKeys?.toShow() != null) dialog = Dialog.ShowRecoveryKey(next)
    }

    /** Writes an encrypted backup to [file], under the recovery key (through a temporary file, so a failed backup never leaves half a file). */
    suspend fun backup(file: java.io.File): Boolean {
        val bk = backups() ?: return false
        val key = recoveryKeys?.current() ?: run { message = "This computer has no recovery key yet. Choose Back up again." to true; return false }
        val tmp = java.io.File(file.absoluteFile.parentFile, file.name + ".part")
        return try {
            val s = tmp.outputStream().use { bk.write(it, key) }
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            message = "Backed up to ${file.name}: ${s.accounts} account${if (s.accounts == 1) "" else "s"}, ${s.envelopes} envelopes, ${s.transactions} transaction${if (s.transactions == 1) "" else "s"}${photosLine(s.photos)}." to false
            true
        } catch (e: Exception) {
            tmp.delete()
            message = "The backup couldn't be written to ${file.name}. Nothing was saved." to true
            false
        }
    }

    /** The backup a server login makes first: [file], under [key], written through a temporary file. Throws when it can't be written. */
    suspend fun writeBackup(file: java.io.File, key: ByteArray) {
        val bk = backups() ?: throw IllegalStateException("the budget isn't open")
        val tmp = java.io.File(file.absoluteFile.parentFile, file.name + ".part")
        try {
            tmp.outputStream().use { bk.write(it, key) }
            java.nio.file.Files.move(tmp.toPath(), file.toPath())
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Restore (0.12.0): the recovery key saved on this computer that [file] opens with (the account's key first, then
     * this computer's older keys), or null when none of them is it and the key is typed.
     */
    fun savedKeyFor(file: java.io.File): ByteArray? {
        val head = runCatching { file.inputStream().use { it.readNBytes(net.darkrelay.deaddropfinance.backup.BackupCrypto.HEAD_BYTES) } }.getOrNull() ?: return null
        return recoveryKeys?.savedKeyFor(net.darkrelay.deaddropfinance.backup.BackupCrypto.keyIdOf(head))
    }

    /** What a backup file opens with: the recovery key, or (made before 0.11.0) its password; null when it isn't one. */
    fun backupKind(file: java.io.File): Class<out net.darkrelay.deaddropfinance.backup.BackupSecret>? =
        runCatching { file.inputStream().use { net.darkrelay.deaddropfinance.backup.BackupCrypto.kindOf(it.readNBytes(7)) } }.getOrNull()

    /**
     * Replaces the budget with the backup in [file]. A wrong key or password, or a bad file, changes nothing. Logged in to a
     * server (0.12.2), the restored budget also becomes the server's master copy and the other devices take it at their next
     * sync (ServerSync.restoreAsMaster); a refusal before the restore, such as another device editing or a server that can't
     * back up its own budget first, changes nothing anywhere.
     */
    suspend fun restore(file: java.io.File, opener: net.darkrelay.deaddropfinance.backup.BackupSecret): Boolean {
        val bk = backups() ?: return false
        val srv = server?.takeIf { it.connected }
        return try {
            var summary: net.darkrelay.deaddropfinance.backup.DesktopBackups.Summary? = null
            val local: suspend () -> Unit = {
                summary = file.inputStream().use { bk.restore(it, opener) }
                refresh()
                monthIndex = 0
                drawerEnvelope = null
            }
            if (srv != null) srv.sync.restoreAsMaster(local) else local()
            val s = summary!!
            message = "Restored from ${file.name}: ${s.accounts} account${if (s.accounts == 1) "" else "s"}, ${s.envelopes} envelopes, ${s.transactions} transaction${if (s.transactions == 1) "" else "s"}${photosLine(s.photos)}." +
                (if (srv != null) " The server's budget is now this backup, and your other devices take it at their next sync." else "") to false
            true
        } catch (e: net.darkrelay.deaddropfinance.backup.BackupException) {
            message = "${e.message} Your budget wasn't changed." to true
            false
        } catch (e: net.darkrelay.deaddropfinance.serversync.ServerException) {
            message = (e.message ?: "The server refused.") to true
            false
        } catch (e: net.darkrelay.deaddropfinance.serversync.ServerClient.RpcError) {
            message = (e.message ?: "The server refused.") to true
            false
        } catch (e: java.io.FileNotFoundException) {
            // 0.12.3: what failed, never the file's name or path
            message = "That file couldn't be opened: it may have been moved, or this computer isn't allowed to read it. Your budget wasn't changed." to true
            false
        } catch (e: java.nio.file.AccessDeniedException) {
            message = "This computer isn't allowed to read that file. Your budget wasn't changed." to true
            false
        } catch (e: java.io.IOException) {
            message = "The backup file is cut short or damaged. Your budget wasn't changed." to true
            false
        } catch (e: Exception) {
            message = "The restore stopped on an unexpected problem (${e::class.simpleName}). Your budget wasn't changed." to true
            false
        }
    }

    fun envelopesInTable(b: Budget): List<EnvelopeEntity> = b.fillOrder

    /** What the envelope's mode aims for: Fill to target's target, Goal's amount (as the phone shows it). */
    fun targetOf(e: EnvelopeEntity): Long? = when (e.mode) {
        EnvelopeMode.FILL_TO_TARGET -> e.target
        EnvelopeMode.GOAL -> e.goalAmount
        else -> null
    }

    /** Assigned to [e] in the shown budget month: out of Ready to Assign into it, less what went back. */
    fun assignedTo(b: Budget, e: EnvelopeEntity): Long = b.moves.filter { month.contains(it.date) }.sumOf { m ->
        when {
            m.fromEnvelopeId == null && m.toEnvelopeId == e.id -> m.amount
            m.toEnvelopeId == null && m.fromEnvelopeId == e.id -> -m.amount
            else -> 0L
        }
    }

    fun spendingIn(b: Budget): Pair<Long, Int> {
        val total = b.categoryTotal(month)
        val count = b.transactions.count { it.kind == TransactionKind.NORMAL && month.contains(it.date) && !it.isWash && it.amount < 0 && b.isOnBudget(it.accountId) }
        return total to count
    }

    fun activityTabs(b: Budget): List<AccountEntity> = b.accounts.filter { !it.archived && it.showInActivity }
}
