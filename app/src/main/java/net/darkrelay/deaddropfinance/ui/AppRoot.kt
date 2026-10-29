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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.sync.SyncSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import net.darkrelay.deaddropfinance.domain.CategoryActions
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
    /** Move money; [backToReady] starts it as a move back into Ready to Assign (fixing an over-assigned budget). */
    /** [intoId]: opened from that envelope's screen, so it starts as Ready to Assign into it. */
    data class MoveMoney(val intoId: String?, val backToReady: Boolean = false) : Nav
    data class Assign(val shortfalls: List<Payday.Shortfall>? = null) : Nav
    /** [envelopeId]: opened from that envelope's Add activity, so it starts with the envelope chosen (1.3.0). */
    data class TxEdit(val id: String?, val split: Boolean = false, val accountId: String? = null, val envelopeId: String? = null) : Nav
    data class TxDetail(val id: String) : Nav
    /** Bonus income: a new one ([id] null) or an existing one to edit or delete. */
    data class Bonus(val id: String?) : Nav
    data object Accounts : Nav
    /** Spending per category by budget month (a tab). */
    data object Categories : Nav
    /** One category's purchases ([categoryId] null: Uncategorized) in a budget month ([monthIndex] 0 is the current one). */
    data class CategoryPurchases(val categoryId: String?, val monthIndex: Int) : Nav
    data object ManageCategories : Nav
    data class AccountEdit(val id: String?) : Nav
    data object Import : Nav
    data object Erase : Nav
    data object Purge : Nav
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
    data object Bills : Nav
    data class Bill(val id: String) : Nav
    /** A bill to edit, or a new one ([id] null), optionally made from a repeating charge ([fromSeries]). */
    data class BillEdit(val id: String?, val fromSeries: String? = null) : Nav
    data object Subscriptions : Nav
    data class Subscription(val key: String) : Nav
    data object Forecast : Nav
    data object Reading : Nav
    data object ParkedBuys : Nav
    data object Backup : Nav
    data object SetupImport : Nav
    data object About : Nav
    data object Licenses : Nav
    data class Document(val name: String) : Nav
    /** Which envelopes Safe to Spend counts. */
    data object SafeToSpend : Nav
}

/** Where a notification opens the app ([where]: MainActivity.OPEN_*); [n] rises with each one. */
data class OpenTo(val where: String, val n: Int)

/** Images shared to the app from another app, to scan as a receipt. [n] rises with each share. */
data class SharedReceipt(val uris: List<android.net.Uri>, val n: Int)

/** The phone-side hooks the screens need but can't do in Compose (browser, file picker, settings). */
interface Platform {
    fun openUrl(url: String)
    fun pickCsv(onPicked: (name: String, text: String?) -> Unit)
    /** The system's file picker for a setup JSON file; [onPicked] gets its name and text (null if it couldn't be read). */
    fun pickJson(onPicked: (name: String, text: String?) -> Unit)
    /** Saves docs/IMPORT.md's invented example setup file through the system save picker; [done] says whether it worked. */
    fun exportSetupExample(done: (Boolean) -> Unit)
    /**
     * Downloads the release APK, verifies it against the published checksum, then installs it (Android's
     * PackageInstaller). Never throws, never touches budget data; every step is reported through [onEvent].
     */
    fun downloadAndInstallUpdate(onEvent: (net.darkrelay.deaddropfinance.UpdateInstallEvent) -> Unit)
    /** The phone is on a metered network (mobile data, or a Wi-Fi marked metered). */
    fun isMeteredNetwork(): Boolean
    /** The release file's size in bytes (null if it can't be told); [onSize] runs on the main thread. */
    fun updateDownloadSize(onSize: (Long?) -> Unit)
    /** Opens Android's "install unknown apps" screen for this app. */
    fun openInstallPermissionSettings()
    /** Erase everything and start over (the activity shows what's next). */
    fun eraseEverything()
    /** Purge data: everything goes; the app then starts at the setup wizard. */
    fun purgeData()
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
    /** Android's notification access for the bank notification reader is granted. */
    fun notifAccessGranted(): Boolean
    /** Turns reading bank notifications on or off (the listener itself, not only a setting). */
    fun setNotifReading(on: Boolean)
    fun openNotifAccess()
    fun openAppInfo()
    /** The phone's apps with a launcher icon (package, name), for picking a bank app. */
    fun launcherApps(): List<Pair<String, String>>
    /** Picked apps' notifications waiting to be checked (in memory only). */
    fun notifPending(): Map<String, String>
    fun clearNotifPending(pkg: String)
    /** The documents and license texts the app carries (assets/licenses), by file name. */
    fun docs(): List<String>
    /** One carried document's text, or null. */
    fun readDoc(name: String): String?
    /** The system's save picker for a new backup file named [name]; null if cancelled. */
    fun createBackupFile(name: String, done: (android.net.Uri?) -> Unit)
    /** The system's file picker for a backup to restore; null if cancelled. */
    fun pickBackupFile(done: (android.net.Uri?) -> Unit)
    /** A picked file's name as the phone shows it. */
    fun displayName(uri: android.net.Uri): String
    /** Writes the backup to [uri]. Runs off the main thread. */
    suspend fun writeBackup(uri: android.net.Uri, passphrase: CharArray): net.darkrelay.deaddropfinance.backup.Backups.Summary
    /** Restores from [uri]: everything is replaced, or (BackupException) nothing changes. Runs off the main thread. */
    suspend fun restoreBackup(uri: android.net.Uri, passphrase: CharArray): net.darkrelay.deaddropfinance.backup.Backups.Summary
}

fun budgetFlow(store: BudgetStore): Flow<Budget> {
    val d = store.dao
    return combine(d.accounts(), d.envelopes(), d.transactions(), d.lines(), d.moves(), d.bills(), d.billPayments(), d.categories(), d.metaFlow(BudgetStore.META_BUDGET_START)) { a ->
        @Suppress("UNCHECKED_CAST")
        Budget(a[0] as List<net.darkrelay.deaddropfinance.data.AccountEntity>, a[1] as List<net.darkrelay.deaddropfinance.data.EnvelopeEntity>,
            a[2] as List<TransactionEntity>, a[3] as List<net.darkrelay.deaddropfinance.data.TransactionLineEntity>,
            a[4] as List<net.darkrelay.deaddropfinance.data.MoveEntity>, a[5] as List<net.darkrelay.deaddropfinance.data.BillEntity>,
            a[6] as List<net.darkrelay.deaddropfinance.data.BillPaymentEntity>, a[7] as List<net.darkrelay.deaddropfinance.data.CategoryEntity>,
            (a[8] as String?)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    store: BudgetStore, appSettings: SyncSettings, platform: Platform, startupUpdateCheck: Boolean, openTo: OpenTo? = null, shared: SharedReceipt? = null,
    /** Called once a shared file or a notification's destination is taken, so it isn't taken again later. */
    onTaken: () -> Unit = {},
    /** Rises each time the app comes back to the front: what Android's settings may have changed is read again. */
    resumeTick: Int = 0,
) {
    val actions = remember(store) { BudgetActions(store, platform.receiptFiles).also { it.requireCategories = true } }
    val sorting = remember(store) { Sorting(store, actions) }
    val categoryActions = remember(store) { CategoryActions(store) }
    var categoryMonth by remember { mutableIntStateOf(0) }
    /** Activity's selected account tab; null is All, where Activity opens. */
    var activityTab by remember { mutableStateOf<String?>(null) }
    val receiptsOps = remember(store) { net.darkrelay.deaddropfinance.domain.Receipts(store, actions, platform.receiptFiles) }
    val billActions = remember(store) { net.darkrelay.deaddropfinance.domain.BillActions(store, actions) }
    val parkedOps = remember(store) { net.darkrelay.deaddropfinance.domain.ParkedBuys(store, actions) }
    val parked by remember(store) { store.dao.parkedBuys() }.collectAsState(initial = emptyList())
    var parkWait by remember { mutableIntStateOf(1) }
    var backupState by remember { mutableStateOf(BackupState()) }
    var importSetup by remember { mutableStateOf(SetupImportState()) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // a parked buy turns ready with time alone, so the clock is checked every minute
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val subscriptions by remember(store) { store.dao.subscriptions() }.collectAsState(initial = emptyList())
    val notifApps by remember(store) { store.dao.notifApps() }.collectAsState(initial = emptyList())
    // bumped when something outside Compose changes (the payday correction, reading access, the pending inbox)
    var planTick by remember { mutableIntStateOf(0) }
    var readingOn by remember { mutableStateOf(appSettings.notifReading) }
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
    var autoApprove by remember { mutableStateOf(appSettings.autoApprove) }
    // bumped when an alert setting or the permission changes; the settings themselves aren't Compose state
    var alertTick by remember { mutableIntStateOf(0) }
    var alertsAsked by remember { mutableStateOf(appSettings.alertsAsked) }
    val budget by remember(store) { budgetFlow(store) }.collectAsState(initial = null)
    val stack = remember { mutableStateListOf<Nav>(Nav.Home) }
    var error by remember { mutableStateOf<String?>(null) }
    // a user action is running (run): another tap waits for it
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<UpdateCheck.Release?>(null) }
    var updateInstall by remember { mutableStateOf(UpdateInstallState()) }
    var updateFromMore by remember { mutableStateOf(false) }
    var mobileDataAsk by remember { mutableStateOf<net.darkrelay.deaddropfinance.MobileDataAsk?>(null) }
    var more by remember { mutableStateOf(platform.more) }
    var importState by remember { mutableStateOf(ImportState()) }
    var washCandidates by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()

    // setup
    var erasing by remember { mutableStateOf(false) }
    var setupDone by remember { mutableStateOf(appSettings.setupDone) }
    var cardDismissed by remember { mutableStateOf(appSettings.setupCardDismissed) }
    var wizard by remember { mutableStateOf<WizardPage?>(null) }
    var wizardForced by remember { mutableStateOf(false) }
    var starters by remember { mutableStateOf(setOf<String>()) }

    fun go(n: Nav) { error = null; message = null; stack.add(n) }
    fun back() { error = null; message = null; if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(n: Nav) { error = null; message = null; if (n == Nav.Activity) activityTab = null; stack.clear(); stack.add(n) }
    fun backTo(n: Nav) { while (stack.size > 1 && stack.last() != n) stack.removeAt(stack.lastIndex) }
    /**
     * Runs a user action: one at a time (a second tap while it runs is ignored, so nothing is saved twice), with the
     * last message cleared first, and any failure shown as an error instead of closing the app.
     */
    fun run(scope: CoroutineScope, popAfter: Boolean = false, block: suspend () -> Unit) = scope.launch {
        if (working) return@launch
        working = true
        message = null
        try {
            block(); error = null
            if (popAfter) back()
        } catch (e: BudgetRuleException) {
            error = e.message
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "That didn't work. Try again."
        } finally {
            working = false
        }
    }
    /** After a restore: the settings it brought are read again, and applied where the phone needs it. */
    fun reloadSettings() {
        val st = appSettings
        st.setupDone = true
        setupDone = true
        autoApprove = st.autoApprove
        alertsAsked = st.alertsAsked
        more = platform.more
        platform.setHide(more.hideScreen)
        alertTick++
        planTick++
        scope.launch { parkWait = parkedOps.defaultWait() }
    }
    /** The settings a setup file changes, applied where each one lives. */
    fun applySettings(s: net.darkrelay.deaddropfinance.domain.SetupImport.SettingsIn) {
        val st = appSettings
        s.autoApprove?.let { st.autoApprove = it; autoApprove = it }
        s.alertSpending?.let { st.alertSpending = it }
        s.alertLowOrOverspent?.let { st.alertLowOrOverspent = it }
        s.alertSafeToSpendNegative?.let { st.alertSafeToSpend = it }
        s.alertAmountsOnLockScreen?.let { st.alertAmountsOnLockScreen = it }
        s.alertBillReminders?.let { st.alertBills = it }
        s.alertAutopayWarnings?.let { st.alertAutopay = it }
        s.appLock?.let { platform.setLock(it) }
        s.lockAfterMinutes?.let { platform.setTimeout(it) }
        s.hideScreen?.let { platform.setHide(it) }
        s.checkForUpdatesAtStartup?.let { platform.setCheckAtStartup(it) }
        s.parkingLotWait?.let { parkWait = it }
        more = platform.more
        alertTick++
    }
    fun lastBackupText(): String? = appSettings.lastBackupAt.takeIf { it > 0 }?.let { at ->
        "${whenText(at)}, ${appSettings.lastBackupName}"
    }
    fun openWizard() { starters = emptySet(); wizard = WizardPage.WELCOME }

    LaunchedEffect(Unit) {
        store.ensureSystemEnvelopes()
        store.ensureCategories()
        // 1.1.1: an install that was already budgeting starts its budget today; a new one starts when setup finishes
        if (store.ensureBudgetStart(java.time.LocalDate.now(), appSettings.setupDone)) runCatching { sorting.labelHistory() }
        // setup is required on a fresh install; an install that already has accounts gets the Home card instead
        val afterPurge = appSettings.setupAfterPurge
        val mode = setupMode(appSettings.setupDone, appSettings.setupCardDismissed, store.dao.accountsNow().isNotEmpty(), afterPurge)
        if (mode == SetupMode.WIZARD) {
            openWizard()
            wizardForced = true
        }
        if (startupUpdateCheck) {
            delay(net.darkrelay.deaddropfinance.Updates.STARTUP_DELAY_MS)
            platform.checkForUpdates(manual = false) { r, _ -> release = r }
        }
    }
    fun handleUpdateEvent(event: net.darkrelay.deaddropfinance.UpdateInstallEvent) {
        updateInstall = when (event) {
            is net.darkrelay.deaddropfinance.UpdateInstallEvent.Downloading ->
                updateInstall.copy(working = true, percent = event.percent, needsPermission = false, message = null, failed = false)
            net.darkrelay.deaddropfinance.UpdateInstallEvent.NeedsInstallPermission ->
                updateInstall.copy(working = false, percent = null, needsPermission = true, message = "Allow installs, then come back here to continue.", failed = false)
            is net.darkrelay.deaddropfinance.UpdateInstallEvent.Done ->
                updateInstall.copy(working = false, percent = null, needsPermission = false, message = event.message, failed = !event.ok)
        }
    }
    /** The update bar with its mobile data question: shown on Home after the startup check, and on More after Check for updates. */
    @Composable
    fun updateBar(r: UpdateCheck.Release) {
        UpdateBar(
            r, updateInstall, onNotes = { platform.openUrl(r.notesUrl) },
            onDownload = {
                updateInstall = UpdateInstallState(working = true)
                net.darkrelay.deaddropfinance.UpdateStart.begin(
                    platform.isMeteredNetwork(),
                    ask = { platform.updateDownloadSize { bytes -> mobileDataAsk = net.darkrelay.deaddropfinance.MobileDataAsk(bytes) } },
                    start = { platform.downloadAndInstallUpdate(::handleUpdateEvent) },
                )
            },
            onAllowInstalls = { platform.openInstallPermissionSettings() },
            onSkip = { platform.skipVersion(r.version); release = null; updateFromMore = false; updateInstall = UpdateInstallState() },
            onLater = { release = null; updateFromMore = false; updateInstall = UpdateInstallState() },
        )
        mobileDataAsk?.let { ask ->
            MobileDataDialog(
                ask.bytes,
                onDownload = { mobileDataAsk = null; platform.downloadAndInstallUpdate(::handleUpdateEvent) },
                onCancel = { mobileDataAsk = null; updateInstall = UpdateInstallState() },
            )
        }
    }
    LaunchedEffect(resumeTick) {
        alertTick++
        // back from "Install unknown apps": the verified download is still there, so this continues straight to installing
        if (updateInstall.needsPermission) {
            updateInstall = updateInstall.copy(working = true, needsPermission = false, message = null)
            platform.downloadAndInstallUpdate(::handleUpdateEvent)
        }
    }
    LaunchedEffect(openTo?.n) {
        if (openTo != null) onTaken()
        when (openTo?.where) {
            null -> {}
            net.darkrelay.deaddropfinance.MainActivity.OPEN_BILLS -> tab(Nav.Bills)
            net.darkrelay.deaddropfinance.MainActivity.OPEN_HOME -> tab(Nav.Home)
            net.darkrelay.deaddropfinance.MainActivity.OPEN_READING -> { tab(Nav.More); go(Nav.Reading) }
            else -> tab(Nav.Activity)
        }
    }
    LaunchedEffect(Unit) {
        parkWait = parkedOps.defaultWait()
        while (true) { nowMs = System.currentTimeMillis(); delay(60_000) }
    }
    LaunchedEffect(Unit) {
        // photos left behind by a receipt that's gone (or a scan never saved)
        withContext(Dispatchers.IO) { platform.sweepReceiptFiles(store.dao.receiptsNow().map { it.id }.toSet()) }
    }
    BackHandler(enabled = stack.size > 1) { back() }
    BackHandler(enabled = stack.size == 1 && wizard != null && wizard != WizardPage.WELCOME) {
        wizard = WizardPage.entries[wizard!!.ordinal - 1]
    }

    val b = budget ?: return
    val top = stack.last()
    val hasAccounts = b.accounts.any { !it.archived }
    val suggestions = remember(b, rules) { Suggest.all(b, rules) }
    val correction by produceState<LocalDate?>(null, planTick) { value = billActions.paydayCorrection() }
    val prediction = remember(b.paydayDates, correction, today) { net.darkrelay.deaddropfinance.domain.Paydays.predict(b.paydayDates, today, correction) }
    val nextPayday = prediction.effectiveNext(today)
    val series = remember(b, subscriptions) { net.darkrelay.deaddropfinance.domain.Recurring.detect(b.transactions, subscriptions) }
    val forecast = remember(b, series, nextPayday) { nextPayday?.let { net.darkrelay.deaddropfinance.domain.Forecast.build(b, series, today, it) } }
    val checklist = remember(b, nextPayday) { net.darkrelay.deaddropfinance.domain.Bills.checklist(b.bills, b.billPayments, b.paydayDates.lastOrNull(), nextPayday ?: today.plusDays(14)) }
    fun askNotifications() = platform.requestNotifications { _ ->
        alertTick++
        appSettings.alertsAsked = true
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
        onTaken()
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
                val showCard = setupMode(setupDone, cardDismissed, hasAccounts = true) == SetupMode.CARD
                val r = release
                val head: (@Composable () -> Unit)? = if (!showCard && r == null) null else {
                    {
                        Column {
                            if (showCard) FinishSetupCard(onOpen = { openWizard() }, onDismiss = { appSettings.setupCardDismissed = true; cardDismissed = true })
                            if (r != null) updateBar(r)
                        }
                    }
                }
                val late = prediction.late && prediction.next != null
                HomeScreen(
                    b, top = head,
                    onAssign = { go(Nav.Assign()) }, onToSort = { tab(Nav.Activity) }, onEnvelope = { go(Nav.Envelope(it)) },
                    onAllEnvelopes = { tab(Nav.Envelopes) }, onSafeToSpend = { go(Nav.SafeToSpend) },
                    onFixOverAssigned = { go(Nav.MoveMoney(null, backToReady = true)) },
                    onBonus = { go(Nav.Bonus(null)) },
                    envelopes = net.darkrelay.deaddropfinance.ui.homeEnvelopes(b, today, nextPayday),
                    parkedWaiting = net.darkrelay.deaddropfinance.domain.ParkedBuys.waiting(parked).size,
                    parkedReady = net.darkrelay.deaddropfinance.domain.ParkedBuys.ready(parked, nowMs).size,
                    onParked = { go(Nav.ParkedBuys) },
                    forecast = if (forecast == null && !late) null else {
                        {
                            Column {
                                if (late) LatePaydayNote(prediction.next!!, today)
                                forecast?.let { ForecastCard(it) { go(Nav.Forecast) } }
                            }
                        }
                    },
                    checklist = if (checklist.isEmpty()) null else {
                        {
                            PaydayChecklistCard(
                                checklist,
                                onTick = { c, on -> run(scope) { if (on) billActions.markPaid(c.bill.id, c.due) else billActions.unmarkPaid(c.bill.id, c.due) } },
                                onBill = { go(Nav.Bill(it)) },
                            )
                        }
                    },
                )
            }
            Nav.Envelopes -> EnvelopesScreen(
                b, onEnvelope = { go(Nav.Envelope(it)) }, onAdd = { go(Nav.EnvelopeEdit(null)) }, onMove = { go(Nav.MoveMoney(null)) },
                onReorder = { ids -> run(scope) { actions.reorderEnvelopes(ids) } },
            )
            Nav.Activity -> {
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                ActivityScreen(
                    b, onAdd = { go(Nav.TxEdit(null)) }, onTransaction = { id -> go(if (b.transactions.firstOrNull { it.id == id }?.kind == net.darkrelay.deaddropfinance.data.TransactionKind.BONUS) Nav.Bonus(id) else Nav.TxDetail(id)) },
                    onSort = { id, env, rta -> handSort(id, env, rta) }, onSplit = { go(Nav.TxEdit(it, split = true)) },
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
                        onCategory = { id, c -> run(scope) { offer = sorting.sortByCategory(id, c) as? Sorting.Learned.Offer } },
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
                        stale = net.darkrelay.deaddropfinance.domain.NotifLog.stale(b, today),
                        onKeepStale = { id -> run(scope) { net.darkrelay.deaddropfinance.domain.NotifLog(store, actions).keep(id) } },
                        onDeleteStale = { id -> run(scope) { actions.deleteTransaction(id) } },
                    ),
                    tabs = ActivityTabs(
                        accounts = activityTabAccounts(b),
                        selected = activityTab,
                        onSelect = { activityTab = it },
                        onAdd = { id -> go(Nav.TxEdit(null, accountId = id)) },
                        onScan = { startScan(null) },
                        onImport = { id -> importState = ImportState(accountId = id); go(Nav.Import) },
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
                        updateFromMore = r != null
                        more = platform.more.copy(updateMessage = if (r != null) null else msg)
                    }
                },
                updateBar = release?.takeIf { updateFromMore }?.let { r -> { updateBar(r) } },
                budgetStart = b.budgetStart,
                onBudgetStart = { d -> run(scope) { store.setBudgetStart(d); sorting.labelHistory() } },
                onSetup = { openWizard() },
                onErase = { go(Nav.Erase) },
                onPurge = { go(Nav.Purge) },
                onAbout = { go(Nav.About) },
                onReceipts = { go(Nav.Receipts) },
                onParked = { go(Nav.ParkedBuys) },
                onSetupImport = { importSetup = SetupImportState(); go(Nav.SetupImport) },
                onBackup = { backupState = BackupState(lastBackup = lastBackupText()); restoreUri = null; go(Nav.Backup) },
                backupLine = lastBackupText()?.let { "Last backup $it" } ?: "Encrypted, one file",
                parkWait = parkWait,
                onParkWait = { d -> run(scope) { parkedOps.setDefaultWait(d); parkWait = d } },
                sortAlerts = {
                    val st = appSettings
                    val notificationsAllowed = key(alertTick) { platform.notificationsAllowed() }
                    SortingAndAlertsSection(
                        SortAlertState(
                            rules.size, autoApprove, st.alertSpending, st.alertLowOrOverspent, st.alertSafeToSpend, st.alertAmountsOnLockScreen,
                            notificationsAllowed, st.alertBills, st.alertAutopay, readingOn,
                        ),
                        onReading = { go(Nav.Reading) },
                        onRules = { go(Nav.Rules) },
                        onAutoApprove = { st.autoApprove = it; autoApprove = it },
                        onAlert = { key, on ->
                            when (key) {
                                ALERT_SPENDING -> st.alertSpending = on
                                ALERT_LOW -> st.alertLowOrOverspent = on
                                ALERT_SAFE -> st.alertSafeToSpend = on
                                ALERT_LOCK_AMOUNTS -> st.alertAmountsOnLockScreen = on
                                ALERT_BILLS -> st.alertBills = on
                                ALERT_AUTOPAY -> st.alertAutopay = on
                            }
                            alertTick++
                            if (on && key != ALERT_LOCK_AMOUNTS && !notificationsAllowed) askNotifications()
                        },
                        onAllowNotifications = { askNotifications() },
                    )
                },
            )
            is Nav.Envelope -> {
                val e = b.envelopes.firstOrNull { it.id == top.id }
                if (e == null) Gone(::back) else EnvelopeDetailScreen(
                    e, b, today, onBack = ::back, onEdit = { go(Nav.EnvelopeEdit(e.id)) }, onMove = { go(Nav.MoveMoney(e.id)) },
                    onCover = { run(scope) { actions.coverFromRollover(e.id) } },
                    onUseTarget = { avg -> run(scope) { actions.saveEnvelope(e.id, BudgetActions.EnvelopeDraft(e.name, e.mode, target = avg)) } },
                    onArchive = { a -> run(scope) { actions.setEnvelopeArchived(e.id, a) } }, onTransaction = { go(Nav.TxDetail(it)) },
                    onAddActivity = { go(Nav.TxEdit(null, envelopeId = e.id)) },
                )
            }
            is Nav.EnvelopeEdit -> {
                var chosen by remember(top.id) { mutableStateOf<Set<String>?>(null) }
                val claims = ClaimsUi(
                    b.categories,
                    b.categories.filter { it.envelopeId != null && it.envelopeId != top.id }.associate { c -> c.id to (b.envelopes.firstOrNull { it.id == c.envelopeId }?.name ?: "another envelope") },
                    b.categories.filter { it.envelopeId == top.id && top.id != null }.map { it.id }.toSet(),
                ) { chosen = it }
                EnvelopeEditScreen(
                    b.envelopes.firstOrNull { it.id == top.id }, error, ::back,
                    onSave = { d ->
                        run(scope, popAfter = true) {
                            val id = actions.saveEnvelope(top.id, d)
                            chosen?.let { categoryActions.setClaims(id, it) }
                        }
                    },
                    claims = claims,
                )
            }
            is Nav.MoveMoney -> MoveMoneyScreen(b, top.intoId, error, ::back, backToReady = top.backToReady) { from, to, amount -> run(scope, popAfter = true) { actions.move(from, to, amount) } }
            is Nav.Assign -> AssignScreen(
                b, today, error, ::back,
                onAdd = { id -> run(scope) { if (actions.addToEnvelope(id) == 0L) throw BudgetRuleException("Nothing is ready to assign.") } },
                onAssignAmount = { id, amount -> run(scope) { actions.assign(id, amount) } },
                shortfalls = top.shortfalls,
            )
            is Nav.TxEdit -> TransactionEditScreen(
                b, b.transactions.firstOrNull { it.id == top.id }, top.split, error, ::back, presetAccount = top.accountId, presetEnvelope = top.envelopeId,
                onSave = { d -> run(scope, popAfter = true) { val id = actions.saveTransaction(top.id, d); if (top.id != null) offer = sorting.learnAfter(id) as? Sorting.Learned.Offer } },
                onSaveTransfer = { from, to, amount, date, memo, env -> run(scope, popAfter = true) { actions.saveTransfer(top.id, from, to, amount, date, memo, env) } },
                onSavePayday = { d, fill ->
                    run(scope) {
                        val plan = actions.savePayday(d, fill).second
                        back()
                        if (fill && plan.shortfalls.isNotEmpty()) go(Nav.Assign(plan.shortfalls))
                        message = if (!fill) "Payday: the new budget month starts ${d.date}." else "Payday: ${plan.fills.size} envelopes filled, ${plan.sweeps.size} swept into Rollover." +
                            if (plan.shortfalls.isEmpty()) "" else " ${plan.shortfalls.size} didn't fill."
                    }
                },
            )
            is Nav.Bonus -> {
                val t = top.id?.let { id -> b.transactions.firstOrNull { it.id == id } }
                if (top.id != null && t == null) Gone(::back) else BonusIncomeScreen(
                    t, error, ::back,
                    onSave = { f -> run(scope, popAfter = true) { actions.saveBonus(top.id, f.amount, f.date, f.receivedAs, f.note) } },
                    onDelete = if (t == null) null else { { run(scope, popAfter = true) { actions.deleteTransaction(t.id) } } },
                    today = today,
                )
            }
            is Nav.TxDetail -> {
                val t = b.transactions.firstOrNull { it.id == top.id }
                LaunchedEffect(top.id, b.transactions.size) { washCandidates = actions.washCandidates(top.id) }
                if (t == null) Gone(::back) else TransactionDetailScreen(
                    t, b, washCandidates, message ?: error, ::back, onEdit = { go(Nav.TxEdit(t.id)) },
                    onDelete = { run(scope, popAfter = true) { actions.deleteTransaction(t.id) } },
                    onDeleteWithReceipt = { also -> run(scope, popAfter = true) { actions.deleteTransaction(t.id, deleteReceipt = also) } },
                    onPayday = { mark ->
                        run(scope) {
                            if (mark) {
                                val plan = actions.markPayday(t.id)
                                if (plan.shortfalls.isNotEmpty()) go(Nav.Assign(plan.shortfalls))
                                message = "Payday: ${plan.fills.size} envelopes filled, ${plan.sweeps.size} swept into Rollover." +
                                    if (plan.shortfalls.isEmpty()) "" else " ${plan.shortfalls.size} didn't fill."
                            } else actions.unmarkPayday(t.id)
                        }
                    },
                    onWash = { other -> run(scope) { actions.markWash(t.id, other) } }, onUnwash = { run(scope) { actions.unmarkWash(t.id) } },
                    returnCandidates = remember(b, t.id) { Suggest.returnCandidates(b, t) },
                    onReturn = { p -> returnOf(t.id, p) },
                    onNotReturn = { run(scope) { sorting.notAReturn(t.id) } },
                    onMakeRule = { go(Nav.RuleEdit(null, fromTx = t.id)) },
                    onSetCategory = { lineId, c ->
                        run(scope) {
                            val line = b.lines.firstOrNull { it.id == lineId }
                            // history before the budget start only gets the label: its envelope wouldn't count anyway
                            if (b.beforeStart(t)) actions.setLineCategory(lineId, c)
                            else if (line?.bucket == net.darkrelay.deaddropfinance.data.LineBucket.UNSORTED && c != null) actions.sortIntoCategory(t.id, c)
                            else if (line?.bucket != net.darkrelay.deaddropfinance.data.LineBucket.UNSORTED) actions.setLineCategory(lineId, c)
                        }
                    },
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
                if (form == null) Gone(::back) else ReceiptEditScreen(
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
                if (r == null) Gone(::back) else ReceiptDetailScreen(
                    r, receiptItems.filter { it.receiptId == r.id }, pages, b.transactions.firstOrNull { it.id == r.transactionId }, b, itemReturns, message ?: error,
                    onBack = ::back, onEdit = { go(Nav.ReceiptEdit(r.id, isNew = false)) }, onMatch = { go(Nav.ReceiptMatch(r.id)) },
                    onSplit = { go(Nav.ItemSplit(r.id)) }, onDetach = { run(scope) { receiptsOps.detach(r.id) } },
                    onDelete = { run(scope, popAfter = true) { receiptsOps.delete(r.id) } }, onTransaction = { go(Nav.TxDetail(it)) },
                )
            }
            is Nav.ReceiptMatch -> {
                val r = receipts.firstOrNull { it.id == top.id }
                val list by produceState(emptyList<net.darkrelay.deaddropfinance.domain.ReceiptMatch.Candidate>(), top.id, b.transactions.size) { value = receiptsOps.candidates(top.id) }
                if (r == null) Gone(::back) else ReceiptMatchScreen(r, list, onBack = { back(); if (stack.last() !is Nav.Receipt) go(Nav.Receipt(r.id)) }) { txId ->
                    run(scope) { receiptsOps.attach(r.id, txId); back(); if (stack.last() !is Nav.Receipt) go(Nav.Receipt(r.id)); message = "Matched." }
                }
            }
            is Nav.ItemSplit -> {
                val r = receipts.firstOrNull { it.id == top.receiptId }
                val tx = r?.transactionId?.let { id -> b.transactions.firstOrNull { it.id == id } }
                val items = receiptItems.filter { it.receiptId == top.receiptId }
                if (r == null || tx == null) Gone(::back) else {
                    val txEnv = b.lines.filter { it.transactionId == tx.id }.singleOrNull()?.envelopeId
                    val initial = remember(r.id) { net.darkrelay.deaddropfinance.domain.ItemSplit.suggestions(items, rules, b.envelopes.filter { !it.archived }.map { it.id }.toSet(), txEnv) }
                    ItemSplitScreen(r, items, tx, b, initial, error, ::back) { pick -> run(scope, popAfter = true) { receiptsOps.splitByItems(r.id, pick); message = "Split by items." } }
                }
            }
            Nav.Receipts -> ReceiptsScreen(receipts, b, ::back, onReceipt = { go(Nav.Receipt(it)) }, onScan = { startScan(null) })
            Nav.Categories -> CategoriesScreen(
                b, today, categoryMonth, { categoryMonth = it },
                onCategory = { c -> go(Nav.CategoryPurchases(c, categoryMonth)) },
                onManage = { go(Nav.ManageCategories) },
            )
            is Nav.CategoryPurchases -> CategoryPurchasesScreen(b, today, top.monthIndex, top.categoryId, ::back) { go(Nav.TxDetail(it)) }
            Nav.ManageCategories -> {
                val usage by produceState(emptyMap<String, Int>(), b.categories, b.lines.size, rules.size) {
                    value = b.categories.associate { it.id to categoryActions.usage(it.id) }
                }
                ManageCategoriesScreen(
                    b.categories, { usage[it] ?: 0 }, error, ::back,
                    onAdd = { n -> run(scope) { categoryActions.add(n) } },
                    onRename = { id, n -> run(scope) { categoryActions.rename(id, n) } },
                    onDelete = { id, to -> run(scope) { categoryActions.delete(id, to) } },
                )
            }
            Nav.Bills -> BillsScreen(
                b, today, prediction, series, onBill = { go(Nav.Bill(it)) }, onEnvelope = { go(Nav.Envelope(it)) }, onAdd = { go(Nav.BillEdit(null)) },
                onSubscriptions = { go(Nav.Subscriptions) }, onPayday = { d -> run(scope) { billActions.correctPayday(d); planTick++ } },
            )
            is Nav.Bill -> {
                val bill = b.bills.firstOrNull { it.id == top.id }
                if (bill == null) Gone(::back) else BillDetailScreen(
                    bill, b, today, message ?: error, ::back, onEdit = { go(Nav.BillEdit(bill.id)) },
                    onPaid = { due -> run(scope) { billActions.markPaid(bill.id, due) } },
                    onCash = { due, acct -> run(scope) { billActions.markPaid(bill.id, due, cashAccountId = acct); message = "Logged as paid in cash." } },
                    onUnpay = { due -> run(scope) { billActions.unmarkPaid(bill.id, due) } },
                    onDebt = { due -> run(scope) { billActions.moveRestToDebt(bill.id, due); message = "The rest went to the catch-up debt envelope." } },
                    onTransaction = { go(Nav.TxDetail(it)) },
                )
            }
            is Nav.BillEdit -> {
                val bill = top.id?.let { id -> b.bills.firstOrNull { it.id == id } }
                val s = top.fromSeries?.let { k -> series.firstOrNull { it.key == k } }
                val prefill = s?.let {
                    BillPrefill(
                        it.name, it.amount, it.nextDate,
                        when (it.interval) {
                            net.darkrelay.deaddropfinance.domain.Recurring.Interval.MONTHLY -> net.darkrelay.deaddropfinance.data.BillRecurrence.MONTHLY
                            net.darkrelay.deaddropfinance.domain.Recurring.Interval.QUARTERLY -> net.darkrelay.deaddropfinance.data.BillRecurrence.QUARTERLY
                            net.darkrelay.deaddropfinance.domain.Recurring.Interval.SEMIANNUAL -> net.darkrelay.deaddropfinance.data.BillRecurrence.SEMIANNUAL
                            net.darkrelay.deaddropfinance.domain.Recurring.Interval.YEARLY -> net.darkrelay.deaddropfinance.data.BillRecurrence.YEARLY
                        },
                        it.key,
                    )
                }
                BillEditScreen(
                    bill, prefill, b, error, ::back,
                    onSave = { d -> run(scope, popAfter = true) { billActions.saveBill(top.id, d) } },
                    onDelete = if (bill == null) null else { { run(scope) { billActions.deleteBill(bill.id); stack.removeAll { it is Nav.Bill || it is Nav.BillEdit } } } },
                )
            }
            Nav.Subscriptions -> SubscriptionsScreen(series, ::back) { go(Nav.Subscription(it)) }
            is Nav.Subscription -> {
                val s = series.firstOrNull { top.key in it.keys }
                if (s == null) Gone(::back) else SubscriptionDetailScreen(
                    s, series.filter { it.key != s.key && !it.dismissed }, b, message ?: error, ::back,
                    onConfirm = { run(scope) { billActions.confirm(s) } },
                    onRename = { n -> run(scope) { billActions.rename(s, n); message = "Renamed." } },
                    onMerge = { k -> series.firstOrNull { it.key == k }?.let { o -> run(scope) { billActions.merge(s, o); message = "Merged." } } },
                    onMakeBill = { env -> run(scope) { val id = billActions.makeBill(s, env); go(Nav.Bill(id)); message = "Made a bill. Its payments are matched from now on." } },
                    onDismiss = { run(scope, popAfter = true) { billActions.dismiss(s) } },
                    onRestore = { run(scope) { billActions.restore(s) } },
                )
            }
            Nav.Forecast -> forecast?.let { ForecastScreen(it, ::back) } ?: Gone(::back)
            Nav.ParkedBuys -> ParkedBuysScreen(
                parked, b, nowMs, parkWait, message ?: error, ::back,
                onPark = { n, a, w -> run(scope) { parkedOps.park(n, a, w); nowMs = System.currentTimeMillis(); message = null } },
                onBuy = { id, env ->
                    run(scope) {
                        parkedOps.buy(id, env)
                        message = "Bought. Its charge is sorted to ${b.envelopes.firstOrNull { it.id == env }?.name ?: "that envelope"} when it comes in."
                    }
                },
                onKeep = { id -> run(scope) { parkedOps.keepWaiting(id); nowMs = System.currentTimeMillis(); message = null } },
                onDrop = { id -> run(scope) { parkedOps.drop(id); message = null } },
            )
            Nav.Backup -> BackupScreen(
                backupState, ::back,
                onBackUp = { pw ->
                    val name = net.darkrelay.deaddropfinance.backup.Backups.fileName(today)
                    platform.createBackupFile(name) { uri ->
                        if (uri == null) return@createBackupFile
                        scope.launch {
                            backupState = backupState.copy(working = "Backing up...", message = null, error = null)
                            backupState = try {
                                val s = platform.writeBackup(uri, pw.toCharArray())
                                appSettings.lastBackupAt = System.currentTimeMillis()
                                appSettings.lastBackupName = platform.displayName(uri)
                                backupState.copy(
                                    working = null, lastBackup = lastBackupText(),
                                    message = "Backed up: ${s.accounts} accounts, ${s.transactions} transactions, ${s.receipts} receipts, ${s.photos} photos.",
                                )
                            } catch (e: Exception) {
                                backupState.copy(working = null, error = "The backup couldn't be saved there. Try another place.")
                            }
                        }
                    }
                },
                onPickRestore = {
                    platform.pickBackupFile { uri ->
                        if (uri != null) { restoreUri = uri; backupState = backupState.copy(restoreName = platform.displayName(uri), message = null, error = null) }
                    }
                },
                onRestore = { pw ->
                    val uri = restoreUri
                    if (uri != null) scope.launch {
                        backupState = backupState.copy(working = "Restoring...", message = null, error = null)
                        backupState = try {
                            val s = platform.restoreBackup(uri, pw.toCharArray())
                            reloadSettings()
                            restoreUri = null
                            backupState.copy(
                                working = null, restoreName = null,
                                message = "Restored: ${s.accounts} accounts, ${s.transactions} transactions, ${s.receipts} receipts, ${s.photos} photos.",
                            )
                        } catch (e: net.darkrelay.deaddropfinance.backup.BackupException) {
                            backupState.copy(working = null, error = e.message)
                        } catch (e: Exception) {
                            backupState.copy(working = null, error = "That file couldn't be read. Nothing was changed.")
                        }
                    }
                },
                onCancelRestore = { restoreUri = null; backupState = backupState.copy(restoreName = null, error = null) },
            )
            Nav.SetupImport -> {
                val importer = remember(store) { net.darkrelay.deaddropfinance.domain.SetupImporter(store, actions) }
                SetupImportScreen(
                    importSetup, ::back,
                    onPick = {
                        platform.pickJson { name, text ->
                            if (text == null) { importSetup = SetupImportState(fileName = name, errors = listOf("The file couldn't be read.")); return@pickJson }
                            scope.launch {
                                importSetup = when (val parsed = net.darkrelay.deaddropfinance.domain.SetupImport.parse(text)) {
                                    is net.darkrelay.deaddropfinance.domain.SetupImport.Parsed.Bad -> SetupImportState(fileName = name, errors = parsed.errors)
                                    is net.darkrelay.deaddropfinance.domain.SetupImport.Parsed.Ok -> when (val c = importer.check(parsed.plan)) {
                                        is net.darkrelay.deaddropfinance.domain.SetupImporter.Checked.Bad -> SetupImportState(fileName = name, errors = c.errors)
                                        is net.darkrelay.deaddropfinance.domain.SetupImporter.Checked.Ok -> SetupImportState(fileName = name, plan = parsed.plan, preview = c.preview)
                                    }
                                }
                            }
                        }
                    },
                    onExportExample = {
                        importSetup = importSetup.copy(message = null, errors = emptyList())
                        platform.exportSetupExample { ok ->
                            importSetup = importSetup.copy(
                                message = if (ok) "Saved deaddropfinance-setup-example.json." else null,
                                errors = if (ok) emptyList() else importSetup.errors,
                            )
                        }
                    },
                    onImport = {
                        val plan = importSetup.plan
                        if (plan != null) scope.launch {
                            importSetup = importSetup.copy(working = true)
                            importSetup = try {
                                val p = importer.apply(plan)
                                plan.settings?.let { applySettings(it) }
                                planTick++
                                SetupImportState(message = "Imported: ${p.addCount} added, ${p.updateCount} updated" + if (plan.settings != null) ", settings set." else ".")
                            } catch (e: BudgetRuleException) {
                                importSetup.copy(working = false, preview = null, plan = null, errors = listOf(e.message ?: "It couldn't be imported."))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                importSetup.copy(working = false, preview = null, plan = null, errors = listOf("It couldn't be imported. Nothing was changed."))
                            }
                        }
                    },
                    onCancel = { importSetup = SetupImportState() },
                )
            }
            Nav.About -> AboutScreen(more.version, ::back, platform::openUrl, onLicenses = { go(Nav.Licenses) }, onDoc = { go(Nav.Document(it)) })
            Nav.Licenses -> {
                val files = remember { platform.docs() }
                LicensesScreen(files, ::back) { go(Nav.Document(it)) }
            }
            is Nav.Document -> {
                val text by produceState<String?>(null, top.name) { value = withContext(Dispatchers.IO) { platform.readDoc(top.name) } }
                DocumentScreen(APP_DOCS.firstOrNull { it.first == top.name }?.second ?: top.name, text, ::back)
            }
            Nav.SafeToSpend -> SafeToSpendScreen(b, error, ::back) { id, on -> run(scope) { actions.setEveryday(id, on) } }
            Nav.Reading -> {
                val granted = key(planTick) { platform.notifAccessGranted() }
                val launcher by produceState(emptyList<Pair<String, String>>(), readingOn) { value = withContext(Dispatchers.IO) { platform.launcherApps() } }
                val pending = key(planTick, notifApps) { platform.notifPending() }
                LaunchedEffect(Unit) { while (true) { delay(3000); planTick++ } }
                NotifReadingScreen(
                    ReadingState(readingOn, granted, notifApps, launcher, pending), b, ::back,
                    onToggle = { on ->
                        platform.setNotifReading(on); readingOn = on; planTick++
                        if (on && !platform.notificationsAllowed()) askNotifications()
                    },
                    onOpenAccess = { platform.openNotifAccess() }, onOpenAppInfo = { platform.openAppInfo() },
                    onPick = { pkg, label, acct ->
                        run(scope) {
                            billActions.pickApp(pkg, label, acct)
                            appSettings.notifPackages = store.dao.notifAppsNow().filter { it.enabled }.map { it.packageName }.toSet()
                        }
                    },
                    onDrop = { pkg ->
                        run(scope) {
                            billActions.dropApp(pkg); platform.clearNotifPending(pkg)
                            appSettings.notifPackages = store.dao.notifAppsNow().filter { it.enabled }.map { it.packageName }.toSet()
                        }
                    },
                    onTeach = { pkg, t ->
                        run(scope) {
                            billActions.teach(pkg, t)
                            val text = platform.notifPending()[pkg]
                            platform.clearNotifPending(pkg)
                            val p = text?.let { net.darkrelay.deaddropfinance.domain.NotifParse.parse(it, listOf(t)) }
                            if (p != null) {
                                net.darkrelay.deaddropfinance.domain.NotifLog(store, actions).log(pkg, p)
                                message = "Read and logged."
                            }
                            planTick++
                        }
                    },
                    onForget = { pkg -> run(scope) { billActions.forget(pkg) } },
                    onRefresh = { planTick++ },
                )
            }
            is Nav.ReturnItems -> {
                val credit = b.transactions.firstOrNull { it.id == top.creditId }
                val purchase = b.transactions.firstOrNull { it.id == top.purchaseId }
                val r = receipts.firstOrNull { it.transactionId == top.purchaseId }
                val pre by produceState(emptyMap<String, Long>(), top) { value = receiptsOps.preselect(top.creditId, top.purchaseId) }
                if (credit == null || purchase == null || r == null) Gone(::back) else key(pre) {
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
                if (top.id != null && r == null) Gone(::back) else RuleEditScreen(
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
                    onSave = { f -> run(scope, popAfter = true) { actions.saveAccount(top.id, f.name, f.type, f.startingBalance, f.startDate, f.trackOnly, f.heldAmount, f.showInActivity) } },
                    onArchive = { v -> run(scope, popAfter = true) { actions.setAccountArchived(a!!.id, v) } },
                    budget = b,
                    onDelete = if (a == null) null else {
                        { run(scope) { actions.deleteAccount(a.id); stack.removeAll { it is Nav.AccountEdit } } }
                    },
                    deleteCount = deleteCount,
                )
            }
            Nav.Erase -> EraseScreen(::back, working = erasing) { erasing = true; platform.eraseEverything() }
            Nav.Purge -> PurgeScreen(::back, working = erasing) { erasing = true; platform.purgeData() }
            Nav.Import -> ImportScreen(
                b, importState, ::back,
                onPick = {
                    platform.pickCsv { name, text ->
                        importState = if (text == null) importState.copy(error = "That file couldn't be read.")
                        else ImportState(fileName = name, text = text, layout = CsvImport.detect(text), accountId = importState.accountId ?: b.accounts.firstOrNull { !it.archived }?.id)
                    }
                },
                onLayout = { importState = importState.copy(layout = it, skippedRows = emptySet()) },
                onAccount = { importState = importState.copy(accountId = it, skippedRows = emptySet()) },
                onToggleSkip = { n -> importState = importState.copy(skippedRows = importState.skippedRows.let { if (n in it) it - n else it + n }) },
                onImport = { plan ->
                    val acct = importState.accountId ?: return@ImportScreen
                    run(scope) {
                        val res = actions.importCsv(acct, plan)
                        net.darkrelay.deaddropfinance.domain.Arrivals(store, actions, files = platform.receiptFiles).landed(res.newIds, appSettings.autoApprove)
                        importState = ImportState(result = res)
                    }
                },
            )
        }
    }

    // the setup wizard takes the whole screen; Add account and Import open over it and come back to it
    val page = wizard
    if (page != null && (stack.size == 1 || (top !is Nav.AccountEdit && top != Nav.Import))) {
        SetupWizardHost(
            page, wizardForced, b, starters, more, hasAccounts,
            onPage = { wizard = it },
            onLock = { platform.setLock(it); more = platform.more },
            onAddAccount = { go(Nav.AccountEdit(null)) },
            onImport = { importState = ImportState(); go(Nav.Import) },
            onToggleStarter = { n -> starters = if (n in starters) starters - n else starters + n },
            onAddStarters = {
                val have = b.envelopes.map { it.name.lowercase() }.toSet()
                val chosen = starters.filter { it.lowercase() !in have }
                run(scope) {
                    // groceries and gas are everyday spending (Safe to Spend) from the start
                    for (n in chosen) actions.saveEnvelope(null, BudgetActions.EnvelopeDraft(n, EnvelopeMode.FILL_TO_TARGET, everyday = n in EVERYDAY_STARTERS))
                    starters = emptySet()
                    wizard = WizardPage.FINISH
                }
            },
            onFinish = {
                // the budget starts the day setup finishes (a new install, or after Purge or Erase); running setup again keeps it
                scope.launch { if (store.budgetStart() == null) { store.setBudgetStart(java.time.LocalDate.now()); runCatching { sorting.labelHistory() } } }
                appSettings.setupAfterPurge = false
                appSettings.setupDone = true
                setupDone = true
                wizard = null
                wizardForced = false
                tab(Nav.Home)
            },
        )
        return
    }

    Scaffold(
        bottomBar = { BottomTabs(stack.first()) { tab(it) } },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // screens that don't show errors themselves get them here, so a failed action never goes unsaid
            val shown = top is Nav.Envelope || top in setOf(Nav.Home, Nav.Envelopes, Nav.Activity, Nav.Bills, Nav.Categories, Nav.More, Nav.Rules, Nav.Subscriptions, Nav.Reading, Nav.Receipts)
            val e = error
            if (shown && e != null) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(e, color = DdfColors.Error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { error = null }) { Text("OK") }
                }
            }
            Box(Modifier.fillMaxSize()) { screen(top) }
        }
    }
}

/**
 * The data a screen shows is gone (deleted elsewhere, or not arrived yet just after it was made): back, after a
 * moment, so a row that's just been written has time to arrive and the screen stays.
 */
@Composable
private fun Gone(onGone: () -> Unit) {
    LaunchedEffect(Unit) { delay(400); onGone() }
}

/** Starter envelopes that count in Safe to Spend when the wizard makes them. */
val EVERYDAY_STARTERS = setOf("Groceries", "Gas")

/** A plain money line for messages. */
fun money(c: Long) = Money.format(c)
