package net.darkrelay.deaddropfinance.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.darkrelay.deaddropfinance.UpdateCheck
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetRuleException
import net.darkrelay.deaddropfinance.domain.CsvImport
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.Payday

/** Where the user is. The four tabs are roots; everything else stacks on top. */
sealed interface Nav {
    data object Home : Nav
    data object Envelopes : Nav
    data object Activity : Nav
    data object More : Nav
    data class Envelope(val id: String) : Nav
    data class EnvelopeEdit(val id: String?) : Nav
    data class MoveMoney(val fromId: String?) : Nav
    data class Assign(val shortfalls: List<Payday.Shortfall>? = null) : Nav
    data class TxEdit(val id: String?, val split: Boolean = false) : Nav
    data class TxDetail(val id: String) : Nav
    data object Accounts : Nav
    data class AccountEdit(val id: String?) : Nav
    data object Import : Nav
}

/** The phone-side hooks the screens need but can't do in Compose (browser, file picker, settings). */
interface Platform {
    fun openUrl(url: String)
    fun pickCsv(onPicked: (name: String, text: String?) -> Unit)
    val more: MoreState
    fun setLock(on: Boolean)
    fun setTimeout(minutes: Int)
    fun setHide(on: Boolean)
    fun setCheckAtStartup(on: Boolean)
    /** Runs the update check; [done] gets an available release or null, and a message for a manual check. */
    fun checkForUpdates(manual: Boolean, done: (UpdateCheck.Release?, String?) -> Unit)
    fun skipVersion(version: String)
}

fun budgetFlow(store: BudgetStore): Flow<Budget> {
    val d = store.dao
    return combine(d.accounts(), d.envelopes(), d.transactions(), d.lines(), d.moves(), d.bills(), d.billPayments()) { a ->
        @Suppress("UNCHECKED_CAST")
        Budget(a[0] as List<net.darkrelay.deaddropfinance.data.AccountEntity>, a[1] as List<net.darkrelay.deaddropfinance.data.EnvelopeEntity>,
            a[2] as List<TransactionEntity>, a[3] as List<net.darkrelay.deaddropfinance.data.TransactionLineEntity>,
            a[4] as List<net.darkrelay.deaddropfinance.data.MoveEntity>, a[5] as List<net.darkrelay.deaddropfinance.data.BillEntity>,
            a[6] as List<net.darkrelay.deaddropfinance.data.BillPaymentEntity>)
    }
}

@Composable
fun AppRoot(store: BudgetStore, platform: Platform, startupUpdateCheck: Boolean) {
    val actions = remember(store) { BudgetActions(store) }
    val budget by remember(store) { budgetFlow(store) }.collectAsState(initial = null)
    val stack = remember { mutableStateListOf<Nav>(Nav.Home) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<UpdateCheck.Release?>(null) }
    var more by remember { mutableStateOf(platform.more) }
    var importState by remember { mutableStateOf(ImportState()) }
    var washCandidates by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()

    fun go(n: Nav) { error = null; message = null; stack.add(n) }
    fun back() { error = null; message = null; if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(n: Nav) { error = null; message = null; stack.clear(); stack.add(n) }
    fun run(scope: CoroutineScope, popAfter: Boolean = false, block: suspend () -> Unit) = scope.launch {
        try {
            block(); error = null
            if (popAfter) back()
        } catch (e: BudgetRuleException) {
            error = e.message
        }
    }

    LaunchedEffect(Unit) {
        store.ensureSystemEnvelopes()
        if (startupUpdateCheck) {
            delay(net.darkrelay.deaddropfinance.Updates.STARTUP_DELAY_MS)
            platform.checkForUpdates(manual = false) { r, _ -> release = r }
        }
    }
    BackHandler(enabled = stack.size > 1) { back() }

    val b = budget ?: return
    val top = stack.last()
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(Nav.Home to "Home", Nav.Envelopes to "Envelopes", Nav.Activity to "Activity", Nav.More to "More").forEach { (n, label) ->
                    NavigationBarItem(selected = stack.first() == n, onClick = { tab(n) }, icon = { Text(label.take(1)) }, label = { Text(label) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (top) {
                Nav.Home -> HomeScreen(
                    b,
                    update = release?.let { r ->
                        {
                            UpdateBar(
                                r, onNotes = { platform.openUrl(r.notesUrl) }, onDownload = { platform.openUrl(UpdateCheck.DOWNLOAD_URL) },
                                onSkip = { platform.skipVersion(r.version); release = null }, onLater = { release = null },
                            )
                        }
                    },
                    onAssign = { go(Nav.Assign()) }, onToSort = { tab(Nav.Activity) }, onEnvelope = { go(Nav.Envelope(it)) }, onAddEnvelope = { go(Nav.EnvelopeEdit(null)) },
                )
                Nav.Envelopes -> EnvelopesScreen(
                    b, onEnvelope = { go(Nav.Envelope(it)) }, onAdd = { go(Nav.EnvelopeEdit(null)) }, onMove = { go(Nav.MoveMoney(null)) },
                    onReorder = { ids -> run(scope) { actions.reorderEnvelopes(ids) } },
                )
                Nav.Activity -> ActivityScreen(
                    b, onAdd = { go(Nav.TxEdit(null)) }, onTransaction = { go(Nav.TxDetail(it)) },
                    onSort = { id, env, rta -> run(scope) { actions.sortInto(id, env, rta) } }, onSplit = { go(Nav.TxEdit(it, split = true)) },
                )
                Nav.More -> MoreScreen(
                    more, onAccounts = { go(Nav.Accounts) }, onImport = { importState = ImportState(); go(Nav.Import) },
                    onLock = { platform.setLock(it); more = platform.more }, onTimeout = { platform.setTimeout(it); more = platform.more },
                    onHide = { platform.setHide(it); more = platform.more }, onCheckAtStartup = { platform.setCheckAtStartup(it); more = platform.more },
                    onCheckNow = {
                        more = more.copy(checking = true, updateMessage = null)
                        platform.checkForUpdates(manual = true) { r, msg ->
                            if (r != null) release = r
                            more = platform.more.copy(updateMessage = if (r != null) "Version ${r.version} is available. See Home." else msg)
                        }
                    },
                )
                is Nav.Envelope -> {
                    val e = b.envelopes.firstOrNull { it.id == top.id }
                    if (e == null) back() else EnvelopeDetailScreen(
                        e, b, today, onBack = ::back, onEdit = { go(Nav.EnvelopeEdit(e.id)) }, onMove = { go(Nav.MoveMoney(e.id)) },
                        onCover = { run(scope) { actions.coverFromRollover(e.id) } },
                        onUseTarget = { avg -> run(scope) { actions.saveEnvelope(e.id, BudgetActions.EnvelopeDraft(e.name, e.mode, target = avg)) } },
                        onArchive = { a -> run(scope) { actions.setEnvelopeArchived(e.id, a) } }, onTransaction = { go(Nav.TxDetail(it)) },
                    )
                }
                is Nav.EnvelopeEdit -> EnvelopeEditScreen(b.envelopes.firstOrNull { it.id == top.id }, error, ::back) { d ->
                    run(scope, popAfter = true) { actions.saveEnvelope(top.id, d) }
                }
                is Nav.MoveMoney -> MoveMoneyScreen(b, top.fromId, error, ::back) { from, to, amount -> run(scope, popAfter = true) { actions.move(from, to, amount) } }
                is Nav.Assign -> AssignScreen(
                    b, today, error, ::back,
                    onAdd = { id -> run(scope) { if (actions.addToEnvelope(id) == 0L) throw BudgetRuleException("Nothing is ready to assign.") } },
                    onAssignAmount = { id, amount -> run(scope) { actions.assign(id, amount) } },
                    shortfalls = top.shortfalls,
                )
                is Nav.TxEdit -> TransactionEditScreen(
                    b, b.transactions.firstOrNull { it.id == top.id }, top.split, error, ::back,
                    onSave = { d -> run(scope, popAfter = true) { actions.saveTransaction(top.id, d) } },
                    onSaveTransfer = { from, to, amount, date, memo, env -> run(scope, popAfter = true) { actions.saveTransfer(top.id, from, to, amount, date, memo, env) } },
                )
                is Nav.TxDetail -> {
                    val t = b.transactions.firstOrNull { it.id == top.id }
                    LaunchedEffect(top.id, b.transactions.size) { washCandidates = actions.washCandidates(top.id) }
                    if (t == null) back() else TransactionDetailScreen(
                        t, b, washCandidates, message ?: error, ::back, onEdit = { go(Nav.TxEdit(t.id)) },
                        onDelete = { run(scope, popAfter = true) { actions.deleteTransaction(t.id) } },
                        onPayday = { mark ->
                            run(scope) {
                                if (mark) {
                                    val plan = actions.markPayday(t.id)
                                    message = "Payday: ${plan.fills.size} envelopes filled, ${plan.sweeps.size} swept into Rollover." +
                                        if (plan.shortfalls.isEmpty()) "" else " ${plan.shortfalls.size} didn't fill."
                                    if (plan.shortfalls.isNotEmpty()) go(Nav.Assign(plan.shortfalls))
                                } else actions.unmarkPayday(t.id)
                            }
                        },
                        onWash = { other -> run(scope) { actions.markWash(t.id, other) } }, onUnwash = { run(scope) { actions.unmarkWash(t.id) } },
                    )
                }
                Nav.Accounts -> AccountsScreen(b, ::back, onAccount = { go(Nav.AccountEdit(it)) }, onAdd = { go(Nav.AccountEdit(null)) })
                is Nav.AccountEdit -> {
                    val a = b.accounts.firstOrNull { it.id == top.id }
                    AccountEditScreen(a, error, ::back, onSave = { n, ty, s, d -> run(scope, popAfter = true) { actions.saveAccount(top.id, n, ty, s, d) } },
                        onArchive = { v -> run(scope, popAfter = true) { actions.setAccountArchived(a!!.id, v) } })
                }
                Nav.Import -> ImportScreen(
                    b, importState, ::back,
                    onPick = {
                        platform.pickCsv { name, text ->
                            importState = if (text == null) importState.copy(error = "That file couldn't be read.")
                            else ImportState(fileName = name, text = text, layout = CsvImport.detect(text), accountId = b.accounts.firstOrNull { !it.archived }?.id)
                        }
                    },
                    onLayout = { importState = importState.copy(layout = it) }, onAccount = { importState = importState.copy(accountId = it) },
                    onImport = { plan ->
                        val acct = importState.accountId ?: return@ImportScreen
                        scope.launch { importState = ImportState(result = actions.importCsv(acct, plan)) }
                    },
                )
            }
        }
    }
}

/** A plain money line for messages. */
fun money(c: Long) = Money.format(c)
