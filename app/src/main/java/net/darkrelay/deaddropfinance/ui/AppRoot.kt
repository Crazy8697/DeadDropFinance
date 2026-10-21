package net.darkrelay.deaddropfinance.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.sync.FixCode
import net.darkrelay.deaddropfinance.sync.SyncService
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
import androidx.compose.ui.unit.dp
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
    data object BankSync : Nav
    /** The SimpleFIN connect screen; [fixId] repairs that connection with a new token instead of adding one. */
    data class SyncConnect(val fixId: String? = null) : Nav
    data class SyncLink(val connectionId: String) : Nav
}

/** The phone-side hooks the screens need but can't do in Compose (browser, file picker, settings). */
interface Platform {
    fun openUrl(url: String)
    fun pickCsv(onPicked: (name: String, text: String?) -> Unit)
    /** Puts the background sync job in line with the settings. */
    fun scheduleSync(hasConnections: Boolean)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(store: BudgetStore, sync: SyncService, platform: Platform, startupUpdateCheck: Boolean) {
    val actions = remember(store) { BudgetActions(store) }
    val budget by remember(store) { budgetFlow(store) }.collectAsState(initial = null)
    val connections by remember(store) { store.sync.connections() }.collectAsState(initial = emptyList())
    val links by remember(store) { store.sync.linkedAccounts() }.collectAsState(initial = emptyList())
    val syncLog by remember(store) { store.sync.recentLog(20) }.collectAsState(initial = emptyList())
    val prompt by sync.prompt.collectAsState()
    val syncBusy by sync.busy.collectAsState()
    val stack = remember { mutableStateListOf<Nav>(Nav.Home) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<UpdateCheck.Release?>(null) }
    var more by remember { mutableStateOf(platform.more) }
    var importState by remember { mutableStateOf(ImportState()) }
    var washCandidates by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()

    // bank sync and setup
    val ctl = remember(sync) { SyncController(sync, platform, scope) }
    val connectUi = remember { ConnectUi() }
    val picks = remember { mutableStateMapOf<String, LinkPick>() }
    var linkBusy by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(sync.settings.backgroundEnabled) }
    var interval by remember { mutableStateOf(sync.settings.intervalHours) }
    var setupDone by remember { mutableStateOf(sync.settings.setupDone) }
    var cardDismissed by remember { mutableStateOf(sync.settings.setupCardDismissed) }
    var wizard by remember { mutableStateOf<WizardPage?>(null) }
    var wizardForced by remember { mutableStateOf(false) }
    var wizardDoor by remember { mutableStateOf<Door?>(null) }
    var wizardConn by remember { mutableStateOf<String?>(null) }
    var starters by remember { mutableStateOf(setOf<String>()) }

    fun go(n: Nav) { error = null; message = null; stack.add(n) }
    fun back() { error = null; message = null; if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(n: Nav) { error = null; message = null; stack.clear(); stack.add(n) }
    fun backTo(n: Nav) { while (stack.size > 1 && stack.last() != n) stack.removeAt(stack.lastIndex) }
    fun run(scope: CoroutineScope, popAfter: Boolean = false, block: suspend () -> Unit) = scope.launch {
        try {
            block(); error = null
            if (popAfter) back()
        } catch (e: BudgetRuleException) {
            error = e.message
        }
    }
    fun openWizard() { picks.clear(); wizardConn = null; wizardDoor = null; starters = emptySet(); wizard = WizardPage.WELCOME }
    fun refresh() = scope.launch {
        refreshing = true
        try { sync.syncAll(SyncService.Trigger.MANUAL) } finally { refreshing = false }
    }

    LaunchedEffect(Unit) {
        store.ensureSystemEnvelopes()
        // setup is required on a fresh install; an install that already has accounts gets the Home card instead
        val mode = setupMode(sync.settings.setupDone, sync.settings.setupCardDismissed, store.dao.accountsNow().isNotEmpty(), store.sync.connectionsNow().isNotEmpty())
        if (mode == SetupMode.WIZARD) {
            openWizard()
            wizardForced = true
        }
        platform.scheduleSync(store.sync.connectionsNow().isNotEmpty())
        launch { sync.syncAll(SyncService.Trigger.OPEN) }
        if (startupUpdateCheck) {
            delay(net.darkrelay.deaddropfinance.Updates.STARTUP_DELAY_MS)
            platform.checkForUpdates(manual = false) { r, _ -> release = r }
        }
    }
    BackHandler(enabled = stack.size > 1) { back() }
    BackHandler(enabled = stack.size == 1 && wizard != null && wizard != WizardPage.WELCOME) {
        wizard = WizardPage.entries[wizard!!.ordinal - 1].let { if (it == WizardPage.LINK && wizardConn == null) WizardPage.CONNECT else it }
    }

    val b = budget ?: return
    val top = stack.last()
    val hasAccounts = b.accounts.any { !it.archived }
    @Composable
    fun screen(top: Nav) {
        when (top) {
            Nav.Home -> {
                val showCard = !setupDone && !cardDismissed
                val r = release
                val head: (@Composable () -> Unit)? = if (!showCard && r == null) null else {
                    {
                        Column {
                            if (showCard) FinishSetupCard(onOpen = { openWizard() }, onDismiss = { sync.settings.setupCardDismissed = true; cardDismissed = true })
                            if (r != null) UpdateBar(
                                r, onNotes = { platform.openUrl(r.notesUrl) }, onDownload = { platform.openUrl(UpdateCheck.DOWNLOAD_URL) },
                                onSkip = { platform.skipVersion(r.version); release = null }, onLater = { release = null },
                            )
                        }
                    }
                }
                Refreshable(connections.isNotEmpty(), refreshing, { refresh() }) {
                    HomeScreen(
                        b, update = head,
                        onAssign = { go(Nav.Assign()) }, onToSort = { tab(Nav.Activity) }, onEnvelope = { go(Nav.Envelope(it)) }, onAddEnvelope = { go(Nav.EnvelopeEdit(null)) },
                    )
                }
            }
            Nav.Envelopes -> EnvelopesScreen(
                b, onEnvelope = { go(Nav.Envelope(it)) }, onAdd = { go(Nav.EnvelopeEdit(null)) }, onMove = { go(Nav.MoveMoney(null)) },
                onReorder = { ids -> run(scope) { actions.reorderEnvelopes(ids) } },
            )
            Nav.Activity -> Refreshable(connections.isNotEmpty(), refreshing, { refresh() }) {
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                ActivityScreen(
                    b, onAdd = { go(Nav.TxEdit(null)) }, onTransaction = { go(Nav.TxDetail(it)) },
                    onSort = { id, env, rta -> run(scope) { actions.sortInto(id, env, rta) } }, onSplit = { go(Nav.TxEdit(it, split = true)) },
                    syncNotes = syncLog.filter { it.removed > 0 && it.at > weekAgo }.map { it.message },
                )
            }
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
                onBankSync = { syncMessage = null; go(Nav.BankSync) },
                onSetup = { openWizard() },
                syncLine = if (connections.isEmpty()) null else
                    "${connections.size} connection${if (connections.size == 1) "" else "s"}, last sync ${syncTime(connections.mapNotNull { it.lastSyncAt }.maxOrNull())}" +
                        if (connections.any { it.status == net.darkrelay.deaddropfinance.data.ConnectionStatus.NEEDS_FIX }) ". One needs attention." else "",
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
                AccountEditScreen(
                    a, error, ::back, onSave = { n, ty, s, d -> run(scope, popAfter = true) { actions.saveAccount(top.id, n, ty, s, d) } },
                    onArchive = { v -> run(scope, popAfter = true) { actions.setAccountArchived(a!!.id, v) } },
                    syncSection = if (a == null) null else {
                        { AccountSyncSection(b, a.id, links, connections) { lid, acct -> scope.launch { sync.setLink(lid, acct) } } }
                    },
                )
            }
            Nav.Import -> ImportScreen(
                b, importState, ::back,
                onPick = {
                    platform.pickCsv { name, text ->
                        importState = if (text == null) importState.copy(error = "That file couldn't be read.")
                        else ImportState(fileName = name, text = text, layout = CsvImport.detect(text), accountId = b.accounts.firstOrNull { !it.archived }?.id)
                    }
                },
                onLayout = { importState = importState.copy(layout = it, skippedRows = emptySet()) },
                onAccount = { importState = importState.copy(accountId = it, skippedRows = emptySet()) },
                onToggleSkip = { n -> importState = importState.copy(skippedRows = importState.skippedRows.let { if (n in it) it - n else it + n }) },
                onImport = { plan ->
                    val acct = importState.accountId ?: return@ImportScreen
                    scope.launch { importState = ImportState(result = actions.importCsv(acct, plan)) }
                },
            )
            Nav.BankSync -> {
                val hints by produceState(emptyMap<String, String?>(), connections) {
                    value = withContext(Dispatchers.IO) { connections.associate { it.id to runCatching { sync.secretHint(it) }.getOrNull() } }
                }
                BankSyncScreen(
                    connections, links, b.accounts, hints, background, interval, syncBusy, syncMessage, ::back,
                    onAdd = { syncMessage = null; connectUi.error = null; go(Nav.SyncConnect()) },
                    onSyncNow = { id -> scope.launch { syncMessage = sync.syncConnection(id, SyncService.Trigger.MANUAL).message } },
                    onFix = { c -> fixConnection(c.id, c.fixCode, ctl, connectUi, connections, ::go, scope, sync) },
                    onLinks = { id -> picks.clear(); go(Nav.SyncLink(id)) },
                    onDisconnect = { id, remove ->
                        scope.launch {
                            sync.disconnect(id, remove)
                            platform.scheduleSync(store.sync.connectionsNow().isNotEmpty())
                            syncMessage = if (remove) "Disconnected. Its transactions were removed." else "Disconnected. Its transactions were kept."
                        }
                    },
                    onBackground = { sync.settings.backgroundEnabled = it; background = it; platform.scheduleSync(connections.isNotEmpty()) },
                    onInterval = { sync.settings.intervalHours = it; interval = it; platform.scheduleSync(connections.isNotEmpty()) },
                )
            }
            is Nav.SyncConnect -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                val fix = top.fixId?.let { id -> connections.firstOrNull { it.id == id } }
                ScreenTitle("SimpleFIN", ::back)
                SimpleFinDoor(connectUi, platform::openUrl) {
                    ctl.connectSimpleFin(connectUi, fix?.id) { id ->
                        if (fix != null) { scope.launch { syncMessage = sync.syncConnection(id).message }; backTo(Nav.BankSync) } else { back(); go(Nav.SyncLink(id)) }
                    }
                }
            }
            is Nav.SyncLink -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                ScreenTitle("Link accounts", ::back) { TextButton(onClick = { scope.launch { runCatching { sync.refreshAccounts(top.connectionId) } } }) { Text("Refresh list") } }
                LinkPanel(
                    links.filter { it.connectionId == top.connectionId }, b.accounts, picks, { id, p -> picks[id] = p }, linkBusy, null,
                    onApply = {
                        ctl.applyLinks(top.connectionId, links.filter { it.connectionId == top.connectionId }, b.accounts, picks, { linkBusy = it }) { msg ->
                            syncMessage = msg
                            if (stack.contains(Nav.BankSync)) backTo(Nav.BankSync) else { stack.removeAt(stack.lastIndex); go(Nav.BankSync) }
                        }
                    },
                )
            }
        }
    }

    // the setup wizard takes the whole screen; Add account and Import open over it and come back to it
    val page = wizard
    if (page != null && (stack.size == 1 || (top !is Nav.AccountEdit && top != Nav.Import))) {
        SetupWizardHost(
            page, wizardForced, b, connections, links, connectUi, picks, linkBusy, syncMessage, starters, more, hasAccounts,
            onPage = { wizard = it },
            onDoor = { d -> wizardDoor = d; connectUi.error = null; wizard = WizardPage.CONNECT },
            door = wizardDoor,
            onConnected = { id -> wizardConn = id; picks.clear(); wizard = WizardPage.LINK },
            wizardConn = wizardConn,
            onSimpleFin = { ctl.connectSimpleFin(connectUi, null) { id -> wizardConn = id; picks.clear(); wizard = WizardPage.LINK } },
            onApplyLinks = { id ->
                ctl.applyLinks(id, links.filter { it.connectionId == id }, b.accounts, picks, { linkBusy = it }) { msg -> syncMessage = msg; wizard = WizardPage.STARTER }
            },
            onPick = { id, p -> picks[id] = p },
            onLock = { platform.setLock(it); more = platform.more },
            onAddAccount = { go(Nav.AccountEdit(null)) },
            onImport = { importState = ImportState(); go(Nav.Import) },
            onOpen = platform::openUrl,
            onToggleStarter = { n -> starters = if (n in starters) starters - n else starters + n },
            onAddStarters = {
                val have = b.envelopes.map { it.name.lowercase() }.toSet()
                val chosen = starters.filter { it.lowercase() !in have }
                scope.launch {
                    for (n in chosen) actions.saveEnvelope(null, BudgetActions.EnvelopeDraft(n, EnvelopeMode.FILL_TO_TARGET))
                    starters = emptySet()
                    wizard = WizardPage.FINISH
                }
            },
            onFinish = {
                sync.settings.setupDone = true
                setupDone = true
                wizard = null
                wizardForced = false
                connectUi.clearSecrets()
                tab(Nav.Home)
            },
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(Nav.Home to "Home", Nav.Envelopes to "Envelopes", Nav.Activity to "Activity", Nav.More to "More").forEach { (n, label) ->
                    NavigationBarItem(selected = stack.first() == n, onClick = { tab(n) }, icon = { Text(label.take(1)) }, label = { Text(label) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) { screen(top) }
    }

    val p = prompt
    if (p != null && wizard == null) {
        FixDialog(p, onFix = {
            sync.dismissPrompt()
            fixConnection(p.connectionId, p.code, ctl, connectUi, connections, ::go, scope, sync)
        }, onLater = { sync.dismissPrompt() })
    }
}

/** Sends the user straight to the fix for a connection that needs one. */
private fun fixConnection(
    connectionId: String, code: String?, ctl: SyncController, ui: ConnectUi, connections: List<net.darkrelay.deaddropfinance.data.ConnectionEntity>,
    go: (Nav) -> Unit, scope: CoroutineScope, sync: SyncService,
) {
    val c = connections.firstOrNull { it.id == connectionId } ?: return
    ui.error = null
    when (code) {
        FixCode.SIMPLEFIN_TOKEN -> go(Nav.SyncConnect(c.id))
        else -> go(Nav.BankSync)
    }
}

/** Pull down to sync, when there is a bank connection to sync. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Refreshable(enabled: Boolean, refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { content() }
}

/** A plain money line for messages. */
fun money(c: Long) = Money.format(c)
