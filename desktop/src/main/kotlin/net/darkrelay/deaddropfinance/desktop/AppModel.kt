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
    data class AddTransaction(val accountId: String? = null, val envelopeId: String? = null) : Dialog()
    data class MoveMoney(val fromId: String? = null, val toId: String? = null) : Dialog()
    data class EnvelopeForm(val envelopeId: String? = null) : Dialog()
    data class AccountForm(val accountId: String? = null) : Dialog()
    data class ImportCsv(val accountId: String, val fileName: String, val plan: CsvImport.Plan) : Dialog()
}

/** Where a Move money end points: Ready to Assign (null) or an envelope. */
const val READY_TO_ASSIGN = "__rta__"

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

    val actions: BudgetActions? = engine?.actions?.also { it.requireCategories = false }
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
     * Add transaction: [amount] as typed (positive). Into Ready to Assign it's money in; into an envelope it's spending
     * from that envelope; To sort leaves it for later.
     */
    suspend fun addTransaction(accountId: String, date: LocalDate, payee: String, amount: Long, envelope: String?, note: String): Boolean {
        if (amount <= 0) { message = "Enter an amount above zero." to true; return false }
        val line = when (envelope) {
            READY_TO_ASSIGN -> BudgetActions.LineDraft(null, amount, toReadyToAssign = true)
            null -> null
            else -> BudgetActions.LineDraft(envelope, -amount)
        }
        val signed = if (envelope == READY_TO_ASSIGN) amount else -amount
        return act("Saved.") { saveTransaction(null, BudgetActions.TransactionDraft(accountId, date, payee.trim(), note.trim(), signed, listOfNotNull(line))) }
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
