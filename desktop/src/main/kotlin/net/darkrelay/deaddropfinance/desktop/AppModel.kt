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
    /** Backup: a password typed twice, then where to save. */
    object Backup : Dialog()
    /** Restore from [file]: its password, then a confirmation before the budget is replaced. */
    data class Restore(val file: java.io.File, val confirm: Boolean = false) : Dialog()

    /** The server's dialogs (0.10.0). */
    sealed class ServerDialog : Dialog()
    data class ConfirmLogin(val username: String, val pass: String, val name: String) : ServerDialog() {
        override fun toString() = "ConfirmLogin"
    }
    data class TakeOver(val holder: String) : ServerDialog()
    data class RemoveDevice(val id: String, val name: String) : ServerDialog()
    object Logout : ServerDialog()
    object AddPhone : ServerDialog()
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

    /** Writes an encrypted backup to [file] (through a temporary file, so a failed backup never leaves half a file). */
    suspend fun backup(file: java.io.File, passphrase: String, rounds: Int = net.darkrelay.deaddropfinance.backup.BackupCrypto.ITERATIONS): Boolean {
        val bk = backups() ?: return false
        backupPasswordProblem(passphrase, passphrase)?.let { message = it to true; return false }
        val tmp = java.io.File(file.absoluteFile.parentFile, file.name + ".part")
        return try {
            val s = tmp.outputStream().use { bk.write(it, passphrase.toCharArray(), rounds) }
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            message = "Backed up to ${file.name}: ${s.accounts} account${if (s.accounts == 1) "" else "s"}, ${s.envelopes} envelopes, ${s.transactions} transaction${if (s.transactions == 1) "" else "s"}." to false
            true
        } catch (e: Exception) {
            tmp.delete()
            message = "The backup couldn't be written to ${file.name}. Nothing was saved." to true
            false
        }
    }

    /** The backup a server login makes first: [file], written through a temporary file. Throws when it can't be written. */
    suspend fun writeBackup(file: java.io.File, pass: CharArray, rounds: Int = net.darkrelay.deaddropfinance.backup.BackupCrypto.ITERATIONS) {
        val bk = backups() ?: throw IllegalStateException("the budget isn't open")
        val tmp = java.io.File(file.absoluteFile.parentFile, file.name + ".part")
        try {
            tmp.outputStream().use { bk.write(it, pass, rounds) }
            java.nio.file.Files.move(tmp.toPath(), file.toPath())
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** While connected to the server, a restore waits for a log out (as on the phone): it would replace the budget the server keeps in step. */
    fun canRestore(): Boolean {
        if (server?.connected != true) return true
        message = "Log out of the server (Settings > Server) before restoring a backup: a restore would replace the budget the server keeps in step." to true
        return false
    }

    /** Replaces the budget with the backup in [file]. A wrong password or a bad file changes nothing. */
    suspend fun restore(file: java.io.File, passphrase: String): Boolean {
        if (!canRestore()) return false
        val bk = backups() ?: return false
        return try {
            val s = file.inputStream().use { bk.restore(it, passphrase.toCharArray()) }
            refresh()
            monthIndex = 0
            drawerEnvelope = null
            message = "Restored from ${file.name}: ${s.accounts} account${if (s.accounts == 1) "" else "s"}, ${s.envelopes} envelopes, ${s.transactions} transaction${if (s.transactions == 1) "" else "s"}." to false
            true
        } catch (e: net.darkrelay.deaddropfinance.backup.BackupException) {
            message = "${e.message} Your budget wasn't changed." to true
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
