package net.darkrelay.deaddropfinance.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
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
import net.darkrelay.deaddropfinance.domain.Sorting
import net.darkrelay.deaddropfinance.domain.Suggest

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
    data object Erase : Nav
    data object BankSync : Nav
    /** The SimpleFIN connect screen; [fixId] repairs that connection with a new token instead of adding one. */
    data class SyncConnect(val fixId: String? = null) : Nav
    data class SyncLink(val connectionId: String) : Nav
    data object Rules : Nav
    /** A rule to edit, or a new one ([id] null), optionally started from a transaction. */
    data class RuleEdit(val id: String?, val fromTx: String? = null) : Nav
    /** Scan a receipt, for a transaction or to match later. */
    data class Scan(val forTx: String? = null) : Nav
    /** The receipt form: a new scan ([isNew]) or a saved receipt. */
    data class ReceiptEdit(val id: String, val isNew: Boolean) : Nav
    data class Receipt(val id: String) : Nav
    data class ReceiptMatch(val id: String) : Nav
    data class ItemSplit(val receiptId: String) : Nav
    data object Receipts : Nav
    data class ReturnItems(val creditId: String, val purchaseId: String) : Nav
}

/** Images shared to the app from another app, to scan as a receipt. [n] rises with each share. */
data class SharedReceipt(val uris: List<android.net.Uri>, val n: Int)

/** The phone-side hooks the screens need but can't do in Compose (browser, file picker, settings). */
interface Platform {
    fun openUrl(url: String)
    fun pickCsv(onPicked: (name: String, text: String?) -> Unit)
    /** Puts the background sync job in line with the settings. */
    fun scheduleSync(hasConnections: Boolean)
    /** Erase everything and start over (the activity shows what's next). */
    fun eraseEverything()
    val more: MoreState
    fun setLock(on: Boolean)
    fun setTimeout(minutes: Int)
    fun setHide(on: Boolean)
    fun setCheckAtStartup(on: Boolean)
    /** Runs the update check; [done] gets an available release or null, and a message for a manual check. */
    fun checkForUpdates(manual: Boolean, done: (UpdateCheck.Release?, String?) -> Unit)
    fun skipVersion(version: String)
    /** Spend alerts can be shown (the notification permission is granted and notifications are on). */
    fun notificationsAllowed(): Boolean
    /** Asks for the notification permission; when Android won't ask again, opens the app's notification settings. */
    fun requestNotifications(done: (Boolean) -> Unit)
    /** The camera app takes a photo (no camera permission); null if cancelled. */
    fun takeReceiptPhoto(done: (android.net.Uri?) -> Unit)
    /** An image or a PDF from the phone; null if cancelled. */
    fun pickReceiptFile(done: (android.net.Uri?) -> Unit)
    /** Reads [uri] into receipt pages from [firstPage] on (stored encrypted). Runs off the main thread. */
    suspend fun scanReceipt(uri: android.net.Uri, receiptId: String, firstPage: Int): List<List<net.darkrelay.deaddropfinance.domain.ReceiptParser.Line>>
    /** One stored page, decrypted for showing. */
    suspend fun receiptPage(receiptId: String, page: Int): androidx.compose.ui.graphics.ImageBitmap?
    /** Deletes a receipt's photos. */
    val receiptFiles: net.darkrelay.deaddropfinance.data.ReceiptFiles
    /** Removes photos whose receipt is gone. */
    fun sweepReceiptFiles(keep: Set<String>)
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
fun AppRoot(
    store: BudgetStore, sync: SyncService, platform: Platform, startupUpdateCheck: Boolean, openActivity: Int = 0, shared: SharedReceipt? = null,
) {
    val actions = remember(store) { BudgetActions(store, platform.receiptFiles) }
    val sorting = remember(store) { Sorting(store, actions) }
    val receiptsOps = remember(store) { net.darkrelay.deaddropfinance.domain.Receipts(store, actions, platform.receiptFiles) }
    val receipts by remember(store) { store.dao.receipts() }.collectAsState(initial = emptyList())
    val receiptItems by remember(store) { store.dao.receiptItems() }.collectAsState(initial = emptyList())
    val itemReturns by remember(store) { store.dao.itemReturns() }.collectAsState(initial = emptyList())
    // the receipt being scanned: its id, what it's for, each photo's text, and the form
    var scanId by remember { mutableStateOf<String?>(null) }
    var scanFor by remember { mutableStateOf<String?>(null) }
    val scanPages = remember { mutableStateListOf<List<net.darkrelay.deaddropfinance.domain.ReceiptParser.Line>>() }
    var scanForm by remember { mutableStateOf<ReceiptForm?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val rules by remember(store) { store.dao.rules() }.collectAsState(initial = emptyList())
    var offer by remember { mutableStateOf<Sorting.Learned.Offer?>(null) }
    var autoApprove by remember { mutableStateOf(sync.settings.autoApprove) }
    // bumped when an alert setting or the permission changes; the settings themselves aren't Compose state
    var alertTick by remember { mutableIntStateOf(0) }
    var alertsAsked by remember { mutableStateOf(sync.settings.alertsAsked) }
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
    var erasing by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(sync.settings.backgroundEnabled) }
    var interval by remember { mutableIntStateOf(sync.settings.intervalHours) }
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

    LaunchedEffect(connections.isNotEmpty()) {
        if (connections.isNotEmpty() && !cardDismissed) { sync.settings.setupCardDismissed = true; cardDismissed = true }
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
    LaunchedEffect(openActivity) { if (openActivity > 0) tab(Nav.Activity) }
    LaunchedEffect(Unit) {
        // photos left behind by a receipt that's gone (or a scan never saved)
        withContext(Dispatchers.IO) { platform.sweepReceiptFiles(store.dao.receiptsNow().map { it.id }.toSet()) }
    }
    BackHandler(enabled = stack.size > 1) { back() }
    BackHandler(enabled = stack.size == 1 && wizard != null && wizard != WizardPage.WELCOME) {
        wizard = WizardPage.entries[wizard!!.ordinal - 1].let { if (it == WizardPage.LINK && wizardConn == null) WizardPage.CONNECT else it }
    }

    val b = budget ?: return
    val top = stack.last()
    val hasAccounts = b.accounts.any { !it.archived }
    val suggestions = remember(b, rules) { Suggest.all(b, rules) }
    fun askNotifications() = platform.requestNotifications { _ ->
        alertTick++
        sync.settings.alertsAsked = true
        alertsAsked = true
    }
    /** Starts a new scan (for a transaction, or to match later). */
    fun startScan(forTx: String?) {
        scanId = net.darkrelay.deaddropfinance.data.newId(); scanFor = forTx; scanPages.clear(); scanForm = null; scanning = false
        go(Nav.Scan(forTx))
    }
    /** Reads one more photo or file into the scan, then shows the form. */
    fun addToScan(uri: android.net.Uri?) {
        val id = scanId ?: return
        if (uri == null) return
        scope.launch {
            scanning = true; error = null
            try {
                scanPages += platform.scanReceipt(uri, id, scanPages.size)
                scanForm = ReceiptForm.from(net.darkrelay.deaddropfinance.domain.ReceiptParser.parse(scanPages.toList()))
                if (stack.last() !is Nav.ReceiptEdit) { stack.removeAll { it is Nav.Scan }; stack.add(Nav.ReceiptEdit(id, isNew = true)) }
            } catch (e: Exception) {
                error = "That couldn't be read as a receipt photo or PDF. Try another, or a clearer photo."
            } finally {
                scanning = false
            }
        }
    }
    /** A scan left without saving: its photos go. */
    fun dropScan() { scanId?.let { id -> if (receipts.none { it.id == id }) platform.receiptFiles.delete(id) }; scanId = null; scanForm = null; scanPages.clear() }
    /** A return's purchase has a receipt: pick items; otherwise the whole-purchase return. */
    fun returnOf(creditId: String, purchaseId: String) {
        if (receipts.any { it.transactionId == purchaseId && it.kind == net.darkrelay.deaddropfinance.data.ReceiptKind.PURCHASE }) go(Nav.ReturnItems(creditId, purchaseId))
        else run(scope) { sorting.confirmReturn(creditId, purchaseId) }
    }
    LaunchedEffect(shared?.n) {
        val s = shared ?: return@LaunchedEffect
        if (s.uris.isEmpty()) return@LaunchedEffect
        startScan(null)
        val id = scanId!!
        scanning = true
        try {
            for (u in s.uris) scanPages += platform.scanReceipt(u, id, scanPages.size)
            scanForm = ReceiptForm.from(net.darkrelay.deaddropfinance.domain.ReceiptParser.parse(scanPages.toList()))
            stack.removeAll { it is Nav.Scan }; stack.add(Nav.ReceiptEdit(id, isNew = true))
        } catch (e: Exception) {
            error = "The shared file couldn't be read as a receipt."
        } finally {
            scanning = false
        }
    }

    /** Sorting by hand from To sort, then what learning says (an offer, or a rule made: that one shows from the rules). */
    fun handSort(id: String, env: String?, rta: Boolean, transfer: Boolean = false) = run(scope) {
        offer = sorting.sortByHand(id, env, rta, transfer) as? Sorting.Learned.Offer
    }
    @Composable
    fun screen(top: Nav) {
        when (top) {
            Nav.Home -> {
                val showCard = setupMode(setupDone, cardDismissed, hasAccounts = true, hasConnections = connections.isNotEmpty()) == SetupMode.CARD
                // alerts are offered once bank sync is set up, never at first launch
                val showAlerts = !showCard && connections.isNotEmpty() && !alertsAsked && key(alertTick) { !platform.notificationsAllowed() }
                val r = release
                val head: (@Composable () -> Unit)? = if (!showCard && r == null && !showAlerts) null else {
                    {
                        Column {
                            if (showCard) FinishSetupCard(onOpen = { openWizard() }, onDismiss = { sync.settings.setupCardDismissed = true; cardDismissed = true })
                            if (showAlerts) AlertsCard(onTurnOn = { askNotifications() }, onNotNow = { sync.settings.alertsAsked = true; alertsAsked = true })
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
                    onSort = { id, env, rta -> handSort(id, env, rta) }, onSplit = { go(Nav.TxEdit(it, split = true)) },
                    syncNotes = syncLog.filter { it.removed > 0 && it.at > weekAgo }.map { it.message },
                    sorting = SortingUi(
                        suggestions = suggestions,
                        onApprove = { id ->
                            val s = suggestions[id]
                            val p = s?.returnOf
                            if (s?.basis == Suggest.Basis.RETURN && p != null && receipts.any { it.transactionId == p.id }) go(Nav.ReturnItems(id, p.id))
                            else run(scope) { sorting.approve(id) }
                        },
                        onApproveAll = { run(scope) { sorting.approveAll() } },
                        onTransfer = { id -> handSort(id, null, rta = false, transfer = true) },
                        offer = offer,
                        onOffer = { yes -> val o = offer; offer = null; if (o != null) run(scope) { if (yes) sorting.acceptOffer(o) else sorting.declineOffer(o) } },
                        learned = rules.filter { it.origin == net.darkrelay.deaddropfinance.data.RuleOrigin.LEARNED && !it.acknowledged },
                        onLearned = { id, undo -> run(scope) { if (undo) sorting.undoLearned(id) else sorting.acknowledge(id) } },
                        recentlySorted = b.transactions.filter { it.sortedBy == net.darkrelay.deaddropfinance.data.SortedBy.AUTO && (it.sortedAt ?: 0L) > weekAgo }
                            .sortedByDescending { it.sortedAt }.take(20),
                        rules = rules,
                        onScan = { startScan(null) },
                        waitingReceipts = receipts.count { it.transactionId == null },
                        onReceipts = { go(Nav.Receipts) },
                    ),
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
                onErase = { go(Nav.Erase) },
                onReceipts = { go(Nav.Receipts) },
                sortAlerts = {
                    val st = sync.settings
                    val notificationsAllowed = key(alertTick) { platform.notificationsAllowed() }
                    SortingAndAlertsSection(
                        SortAlertState(
                            rules.size, autoApprove, st.alertSpending, st.alertLowOrOverspent, st.alertSafeToSpend, st.alertAmountsOnLockScreen,
                            notificationsAllowed, background && connections.isNotEmpty(), interval,
                        ),
                        onRules = { go(Nav.Rules) },
                        onAutoApprove = { st.autoApprove = it; autoApprove = it },
                        onAlert = { key, on ->
                            when (key) {
                                ALERT_SPENDING -> st.alertSpending = on
                                ALERT_LOW -> st.alertLowOrOverspent = on
                                ALERT_SAFE -> st.alertSafeToSpend = on
                                ALERT_LOCK_AMOUNTS -> st.alertAmountsOnLockScreen = on
                            }
                            alertTick++
                            if (on && key != ALERT_LOCK_AMOUNTS && !notificationsAllowed) askNotifications()
                        },
                        onAllowNotifications = { askNotifications() },
                    )
                },
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
                onSave = { d -> run(scope, popAfter = true) { val id = actions.saveTransaction(top.id, d); if (top.id != null) offer = sorting.learnAfter(id) as? Sorting.Learned.Offer } },
                onSaveTransfer = { from, to, amount, date, memo, env -> run(scope, popAfter = true) { actions.saveTransfer(top.id, from, to, amount, date, memo, env) } },
            )
            is Nav.TxDetail -> {
                val t = b.transactions.firstOrNull { it.id == top.id }
                LaunchedEffect(top.id, b.transactions.size) { washCandidates = actions.washCandidates(top.id) }
                if (t == null) back() else TransactionDetailScreen(
                    t, b, washCandidates, message ?: error, ::back, onEdit = { go(Nav.TxEdit(t.id)) },
                    onDelete = { run(scope, popAfter = true) { actions.deleteTransaction(t.id) } },
                    onDeleteWithReceipt = { also -> run(scope, popAfter = true) { actions.deleteTransaction(t.id, deleteReceipt = also) } },
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
                    returnCandidates = remember(b, t.id) { Suggest.returnCandidates(b, t) },
                    onReturn = { p -> returnOf(t.id, p) },
                    onNotReturn = { run(scope) { sorting.notAReturn(t.id) } },
                    onMakeRule = { go(Nav.RuleEdit(null, fromTx = t.id)) },
                    receipt = receipts.firstOrNull { it.transactionId == t.id },
                    onReceipt = { receipts.firstOrNull { it.transactionId == t.id }?.let { go(Nav.Receipt(it.id)) } },
                    onAddReceipt = if (t.kind == net.darkrelay.deaddropfinance.data.TransactionKind.NORMAL) { { startScan(t.id) } } else null,
                    onPickItems = t.returnOf?.takeIf { p -> receipts.any { it.transactionId == p } }?.let { p -> { go(Nav.ReturnItems(t.id, p)) } },
                )
            }
            is Nav.Scan -> ScanScreen(
                b.transactions.firstOrNull { it.id == top.forTx }?.let { "${it.payee.ifBlank { "a transaction" }}, ${Money.format(it.amount)}" },
                scanning, error, onBack = { dropScan(); back() },
                onCamera = { platform.takeReceiptPhoto { addToScan(it) } }, onFile = { platform.pickReceiptFile { addToScan(it) } },
            )
            is Nav.ReceiptEdit -> {
                val saved = receipts.firstOrNull { it.id == top.id }
                val form = if (top.isNew) scanForm else remember(top.id) { saved?.let { r -> ReceiptForm.from(r, receiptItems.filter { it.receiptId == r.id }) } }
                val pageCount = if (top.isNew) scanPages.size else saved?.pages ?: 0
                val pages by produceState(emptyList<androidx.compose.ui.graphics.ImageBitmap?>(), top.id, pageCount) {
                    value = (0 until pageCount).map { platform.receiptPage(top.id, it) }
                }
                if (form == null) back() else ReceiptEditScreen(
                    form, pages, top.isNew, scanning, error,
                    onBack = { if (top.isNew) dropScan(); back() },
                    onAddCamera = { platform.takeReceiptPhoto { addToScan(it) } }, onAddFile = { platform.pickReceiptFile { addToScan(it) } },
                    onSave = {
                        run(scope) {
                            val id = receiptsOps.save(top.id, form.draft(pageCount), if (top.isNew) scanFor else null)
                            if (!top.isNew) { back(); return@run }
                            val forTx = scanFor
                            scanId = null; scanForm = null; scanPages.clear()
                            stack.removeAt(stack.lastIndex)
                            if (forTx != null) {
                                go(Nav.Receipt(id))
                                message = "Saved to this transaction."
                            } else when (val d = receiptsOps.match(id)) {
                                is net.darkrelay.deaddropfinance.domain.ReceiptMatch.Decision.One -> { go(Nav.Receipt(id)); message = "Matched to ${d.c.tx.payee.ifBlank { "a transaction" }} on ${d.c.tx.date}." }
                                is net.darkrelay.deaddropfinance.domain.ReceiptMatch.Decision.Several -> go(Nav.ReceiptMatch(id))
                                else -> { go(Nav.Receipt(id)); message = "Saved. It waits in Receipts and is matched when its charge comes in." }
                            }
                        }
                    },
                )
            }
            is Nav.Receipt -> {
                val r = receipts.firstOrNull { it.id == top.id }
                val pages by produceState(emptyList<androidx.compose.ui.graphics.ImageBitmap?>(), top.id, r?.pages) {
                    value = (0 until (r?.pages ?: 0)).map { platform.receiptPage(top.id, it) }
                }
                if (r == null) back() else ReceiptDetailScreen(
                    r, receiptItems.filter { it.receiptId == r.id }, pages, b.transactions.firstOrNull { it.id == r.transactionId }, b, itemReturns, message ?: error,
                    onBack = ::back, onEdit = { go(Nav.ReceiptEdit(r.id, isNew = false)) }, onMatch = { go(Nav.ReceiptMatch(r.id)) },
                    onSplit = { go(Nav.ItemSplit(r.id)) }, onDetach = { run(scope) { receiptsOps.detach(r.id) } },
                    onDelete = { run(scope, popAfter = true) { receiptsOps.delete(r.id) } }, onTransaction = { go(Nav.TxDetail(it)) },
                )
            }
            is Nav.ReceiptMatch -> {
                val r = receipts.firstOrNull { it.id == top.id }
                val list by produceState(emptyList<net.darkrelay.deaddropfinance.domain.ReceiptMatch.Candidate>(), top.id, b.transactions.size) { value = receiptsOps.candidates(top.id) }
                if (r == null) back() else ReceiptMatchScreen(r, list, onBack = { back(); if (stack.last() !is Nav.Receipt) go(Nav.Receipt(r.id)) }) { txId ->
                    run(scope) { receiptsOps.attach(r.id, txId); back(); if (stack.last() !is Nav.Receipt) go(Nav.Receipt(r.id)); message = "Matched." }
                }
            }
            is Nav.ItemSplit -> {
                val r = receipts.firstOrNull { it.id == top.receiptId }
                val tx = r?.transactionId?.let { id -> b.transactions.firstOrNull { it.id == id } }
                val items = receiptItems.filter { it.receiptId == top.receiptId }
                if (r == null || tx == null) back() else {
                    val txEnv = b.lines.filter { it.transactionId == tx.id }.singleOrNull()?.envelopeId
                    val initial = remember(r.id) { net.darkrelay.deaddropfinance.domain.ItemSplit.suggestions(items, rules, b.envelopes.filter { !it.archived }.map { it.id }.toSet(), txEnv) }
                    ItemSplitScreen(r, items, tx, b, initial, error, ::back) { pick -> run(scope, popAfter = true) { receiptsOps.splitByItems(r.id, pick); message = "Split by items." } }
                }
            }
            Nav.Receipts -> ReceiptsScreen(receipts, b, ::back, onReceipt = { go(Nav.Receipt(it)) }, onScan = { startScan(null) })
            is Nav.ReturnItems -> {
                val credit = b.transactions.firstOrNull { it.id == top.creditId }
                val purchase = b.transactions.firstOrNull { it.id == top.purchaseId }
                val r = receipts.firstOrNull { it.transactionId == top.purchaseId }
                val pre by produceState(emptyMap<String, Long>(), top) { value = receiptsOps.preselect(top.creditId, top.purchaseId) }
                if (credit == null || purchase == null || r == null) back() else key(pre) {
                    ReturnItemsScreen(
                        credit, purchase, r, receiptItems.filter { it.receiptId == r.id }, itemReturns, pre, b, error, ::back,
                        onApply = { picks -> run(scope, popAfter = true) { receiptsOps.returnItems(credit.id, purchase.id, picks) } },
                        onWholePurchase = { run(scope, popAfter = true) { sorting.confirmReturn(credit.id, purchase.id) } },
                    )
                }
            }
            Nav.Rules -> RulesScreen(
                rules, b, ::back, onRule = { go(Nav.RuleEdit(it)) }, onAdd = { go(Nav.RuleEdit(null)) },
                onMove = { id, up -> run(scope) { sorting.moveRule(id, up) } },
            )
            is Nav.RuleEdit -> {
                val r = top.id?.let { id -> rules.firstOrNull { it.id == id } }
                val from = top.fromTx?.let { id -> b.transactions.firstOrNull { it.id == id } }
                val prefill = from?.let { t ->
                    val ls = b.lines.filter { it.transactionId == t.id }
                    val target = ls.singleOrNull()?.let { l ->
                        when (l.bucket) {
                            net.darkrelay.deaddropfinance.data.LineBucket.ENVELOPE -> l.envelopeId
                            net.darkrelay.deaddropfinance.data.LineBucket.READY_TO_ASSIGN -> net.darkrelay.deaddropfinance.domain.Rules.RTA
                            net.darkrelay.deaddropfinance.data.LineBucket.TRANSFER -> Sorting.TRANSFER
                            else -> null
                        }
                    } ?: suggestions[t.id]?.lines?.singleOrNull()?.envelopeId
                    RulePrefill(net.darkrelay.deaddropfinance.domain.Payees.key(t), if (t.amount < 0) -1 else 1, null, target)
                }
                if (top.id != null && r == null) back() else RuleEditScreen(
                    r, prefill, b, rules, error, ::back,
                    onSave = { d -> run(scope, popAfter = true) { sorting.saveRule(top.id, d) } },
                    onDelete = if (r == null) null else { { run(scope, popAfter = true) { sorting.deleteRule(r.id) } } },
                )
            }
            Nav.Accounts -> AccountsScreen(b, ::back, onAccount = { go(Nav.AccountEdit(it)) }, onAdd = { go(Nav.AccountEdit(null)) })
            is Nav.AccountEdit -> {
                val a = b.accounts.firstOrNull { it.id == top.id }
                val deleteCount by produceState(0, top.id, b.transactions.size) { value = top.id?.let { actions.deleteAccountCount(it) } ?: 0 }
                AccountEditScreen(
                    a, error, ::back,
                    onSave = { f -> run(scope, popAfter = true) { actions.saveAccount(top.id, f.name, f.type, f.startingBalance, f.startDate, f.trackOnly, f.heldAmount) } },
                    onArchive = { v -> run(scope, popAfter = true) { actions.setAccountArchived(a!!.id, v) } },
                    syncSection = if (a == null) null else {
                        {
                            AccountSyncSection(b, a.id, links, connections, onAdjust = { amount, date -> run(scope) { actions.addAdjustment(a.id, amount, date) } }) { lid, acct, remove ->
                                scope.launch { sync.setLink(lid, acct, remove) }
                            }
                        }
                    },
                    budget = b,
                    onDelete = if (a == null) null else {
                        { run(scope) { actions.deleteAccount(a.id); stack.removeAll { it is Nav.AccountEdit } } }
                    },
                    deleteCount = deleteCount,
                )
            }
            Nav.Erase -> EraseScreen(::back, working = erasing) { erasing = true; platform.eraseEverything() }
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
                    scope.launch {
                        val res = actions.importCsv(acct, plan)
                        if (sync.settings.autoApprove) sorting.autoApprove(res.newIds)
                        if (res.newIds.isNotEmpty()) receiptsOps.matchWaiting()
                        importState = ImportState(result = res)
                    }
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
                    onDisconnect = { id, remove, offBudget ->
                        scope.launch {
                            sync.disconnect(id, remove, offBudget)
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
                    links.filter { it.connectionId == top.connectionId }, b.accounts, picks, { id, p -> picks[id] = p }, linkBusy, null, budget = b,
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
                    // groceries and gas are everyday spending (Safe to Spend) from the start
                    for (n in chosen) actions.saveEnvelope(null, BudgetActions.EnvelopeDraft(n, EnvelopeMode.FILL_TO_TARGET, everyday = n in EVERYDAY_STARTERS))
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
        bottomBar = { BottomTabs(stack.first()) { tab(it) } },
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

/** Starter envelopes that count in Safe to Spend when the wizard makes them. */
val EVERYDAY_STARTERS = setOf("Groceries", "Gas")

/** A plain money line for messages. */
fun money(c: Long) = Money.format(c)
