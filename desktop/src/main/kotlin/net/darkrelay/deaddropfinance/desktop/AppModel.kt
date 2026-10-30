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

enum class Screen(val title: String) { BUDGET("Budget"), ACTIVITY("Activity"), ACCOUNTS("Accounts"), REPORTS("Reports"), SYNC("Sync"), SETTINGS("Settings") }

/** An open dialog: Add transaction, Move money, an envelope or account form, or a CSV import's confirmation. */
sealed class Dialog {
    /** [payday], [fill], [split]: how the dialog starts (Payday ticked, split lines), as when picked by hand. */
    data class AddTransaction(val accountId: String? = null, val envelopeId: String? = null, val payday: Boolean = false, val fill: Boolean = false, val split: Boolean = false) : Dialog()
    data class MoveMoney(val fromId: String? = null, val toId: String? = null) : Dialog()
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

    private fun store(): net.darkrelay.deaddropfinance.data.BudgetStore? = (engine?.opened as? DesktopDatabase.Opened.Ok)?.store

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
    ): Boolean {
        if (amount <= 0) { message = "Enter an amount above zero." to true; return false }
        val line = when (envelope) {
            READY_TO_ASSIGN -> BudgetActions.LineDraft(null, amount, toReadyToAssign = true)
            TO_SORT -> BudgetActions.LineDraft(null, amount)
            else -> BudgetActions.LineDraft(envelope, -amount, categoryId = category)
        }
        val signed = if (envelope == READY_TO_ASSIGN || envelope == TO_SORT) amount else -amount
        val draft = BudgetActions.TransactionDraft(accountId, date, payee.trim(), note.trim(), signed, listOf(line))
        if (payday && envelope != READY_TO_ASSIGN) { message = "Only money in to Ready to assign can be a payday." to true; return false }
        if (!payday) return act("Saved.") { saveTransaction(null, draft) }
        var plan: net.darkrelay.deaddropfinance.domain.Payday.Plan? = null
        val ok = act { plan = savePayday(draft, fill, today()).second }
        val p = plan
        if (ok && p != null) {
            val names = budget?.envelopes?.associate { it.id to it.name }.orEmpty()
            message = when {
                !fill -> "Payday: the new budget month starts $date." + if (p.sweeps.isEmpty()) "" else " ${p.sweeps.size} swept into Rollover."
                p.shortfalls.isEmpty() -> "Payday: ${p.fills.size} envelope${if (p.fills.size == 1) "" else "s"} filled, ${p.sweeps.size} swept into Rollover."
                else -> "Payday: ${p.fills.size} envelope${if (p.fills.size == 1) "" else "s"} filled, ${p.sweeps.size} swept into Rollover. Short: " +
                    p.shortfalls.joinToString(", ") { "${names[it.envelopeId] ?: "an envelope"} ${money(it.short)}" } + "."
            } to false
        }
        return ok
    }

    /** One line of a split purchase: its category (required), its envelope (the category's by default) and amount. */
    data class SplitLine(val category: String?, val envelope: String?, val amount: Long)

    /**
     * A purchase split into lines (one trip: groceries, electronics, fishing), as the phone's split. [income] (0.9.0):
     * money in split across envelopes, Ready to assign (READY_TO_ASSIGN) and To sort (TO_SORT), as the phone's.
     */
    suspend fun addSplit(accountId: String, date: LocalDate, payee: String, note: String, total: Long, lines: List<SplitLine>, income: Boolean = false): Boolean {
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
            return act("Saved.") { saveTransaction(null, BudgetActions.TransactionDraft(accountId, date, payee.trim(), note.trim(), total, drafts)) }
        }
        val drafts = lines.map { BudgetActions.LineDraft(it.envelope, -it.amount, categoryId = it.category) }
        return act("Saved.") { saveTransaction(null, BudgetActions.TransactionDraft(accountId, date, payee.trim(), note.trim(), -total, drafts)) }
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
        act("Saved.") { saveAccount(id, name, type, start, startDate, trackOnly, held, showInActivity) }

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
        val ok = act { result = importCsv(d.accountId, d.plan) }
        result?.let { r -> message = "Imported ${r.imported} transaction${if (r.imported == 1) "" else "s"} into To sort; ${r.skipped} already here, ${r.bad} unreadable." to false }
        return ok
    }

    // ---- what the screens show ----------------------------------------------------------------------------

    // ---- backup and restore (0.8.0) ------------------------------------------------------------------------

    private fun backups(): net.darkrelay.deaddropfinance.backup.DesktopBackups? {
        val ok = engine?.opened as? DesktopDatabase.Opened.Ok ?: return null
        return net.darkrelay.deaddropfinance.backup.DesktopBackups(ok.store.db, ok.file, "desktop-${BuildInfo.VERSION}")
    }

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
            message = "Backed up to ${file.name}: ${s.accounts} account${if (s.accounts == 1) "" else "s"}, ${s.envelopes} envelopes, ${s.transactions} transaction${if (s.transactions == 1) "" else "s"}." to false
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
            message = "Restored from ${file.name}: ${s.accounts} account${if (s.accounts == 1) "" else "s"}, ${s.envelopes} envelopes, ${s.transactions} transaction${if (s.transactions == 1) "" else "s"}." +
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
        } catch (e: Exception) {
            message = "${file.name} can't be read. Your budget wasn't changed." to true
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
