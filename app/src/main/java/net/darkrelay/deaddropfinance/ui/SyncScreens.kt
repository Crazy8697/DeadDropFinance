package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.ConnectionEntity
import net.darkrelay.deaddropfinance.data.ConnectionStatus
import net.darkrelay.deaddropfinance.data.LinkedAccountEntity
import net.darkrelay.deaddropfinance.data.Provider
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.domain.BalanceCheck
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.sync.FixCode
import net.darkrelay.deaddropfinance.sync.SyncService
import net.darkrelay.deaddropfinance.sync.SyncSettings

/** The ways in (FEATURES.md and ROADMAP.md Stage 7). */
enum class Door { EASY, OLD_SCHOOL }

/** Links the door screens open in the real browser. */
object ProviderLinks {
    const val SIMPLEFIN_BRIDGE = "https://beta-bridge.simplefin.org/"
    const val SIMPLEFIN_NEW_TOKEN = "https://beta-bridge.simplefin.org/simplefin/create"
}

/** What the door screens hold while the user fills them in (typed text lives only in memory). */
class ConnectUi {
    var token by mutableStateOf("")
    var tokenMessage by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun clearSecrets() { token = "" }
}

private val whenFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a")
fun syncTime(at: Long?, zone: ZoneId = ZoneId.systemDefault()) = at?.let { Instant.ofEpochMilli(it).atZone(zone).format(whenFormat) } ?: "never"

@Composable
fun DoorList(onPick: (Door) -> Unit, includeOldSchool: Boolean = true) {
    DoorCard("Easy: SimpleFIN", "Sign up with SimpleFIN Bridge, connect your bank there, paste one token here.", "SimpleFIN charges its own fee, paid directly to SimpleFIN.") { onPick(Door.EASY) }
    if (includeOldSchool) DoorCard("Old School: CSV", "No bank connection. Import files you download from your bank, or enter things yourself.", "Costs nothing.") { onPick(Door.OLD_SCHOOL) }
}

@Composable
private fun DoorCard(title: String, line: String, cost: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(line, color = DdfColors.Muted, style = MaterialTheme.typography.bodyMedium)
            Text(cost, color = DdfColors.Cyan, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Easy door: a SimpleFIN Bridge setup token. */
@Composable
fun SimpleFinDoor(ui: ConnectUi, onOpen: (String) -> Unit, onConnect: () -> Unit) {
    Text(
        "SimpleFIN Bridge connects to your bank for a fee you pay to SimpleFIN. The app gets read-only access with a token " +
            "you create there. Your bank login stays with SimpleFIN; DarkRelay never sees it.",
        color = DdfColors.Muted,
    )
    Gap()
    Text("1. Sign up at SimpleFIN Bridge and connect your bank there.")
    TextButton(onClick = { onOpen(ProviderLinks.SIMPLEFIN_BRIDGE) }) { Text("Open SimpleFIN Bridge") }
    Text("2. Create a new setup token on the Bridge and copy it.")
    TextButton(onClick = { onOpen(ProviderLinks.SIMPLEFIN_NEW_TOKEN) }) { Text("New setup token page") }
    Text("3. Paste it here. It works once.")
    OutlinedTextField(value = ui.token, onValueChange = { ui.token = it; ui.tokenMessage = null }, label = { Text("Setup token") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
    ui.tokenMessage?.let { Text(it, color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall) }
    ErrorLine(ui.error)
    Gap()
    Button(onClick = onConnect, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text(if (ui.busy) "Connecting..." else "Connect") }
}

/** Old School door: CSV import, or plain manual entry. */
@Composable
fun OldSchoolDoor(hasAccounts: Boolean, onAddAccount: () -> Unit, onImport: () -> Unit, onManual: () -> Unit) {
    Text("No bank connection. Download a CSV file from your bank or payment app and import it, or enter things yourself.", color = DdfColors.Muted)
    Gap()
    if (!hasAccounts) {
        Text("Add the account first, with its current balance.")
        OutlinedButton(onClick = onAddAccount) { Text("Add an account") }
        Gap()
    }
    Button(onClick = onImport, enabled = hasAccounts, modifier = Modifier.fillMaxWidth()) { Text("Import a CSV file") }
    Gap()
    OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("No sync, I'll enter things myself") }
}

/**
 * One provider account's link choice: "new", "track" (a new track-only account), "ignore" or an app account id.
 * [removeFromBudget]: when an account that was linked is set to "ignore", take its app account off the budget.
 */
data class LinkPick(
    val target: String,
    val history: SyncService.History = SyncService.History.NEWEST,
    val removeFromBudget: Boolean = true,
    /** The bank sends this account's balance with the wrong sign (an overdrawn balance as positive). Kept with the bank's account. */
    val actuallyNegative: Boolean = false,
)

fun defaultPick(l: LinkedAccountEntity, accounts: List<AccountEntity>): LinkPick = basePick(l, accounts).copy(actuallyNegative = l.balanceSignFlipped)

private fun basePick(l: LinkedAccountEntity, accounts: List<AccountEntity>): LinkPick {
    val open = accounts.filter { !it.archived }
    l.accountId?.let { id -> if (open.any { it.id == id }) return LinkPick(id) }
    if (l.historyFrom != null || l.lastSyncedDate != null) return LinkPick("ignore")
    open.firstOrNull { it.name.equals(l.providerName, ignoreCase = true) }?.let { return LinkPick(it.id) }
    return LinkPick(if (open.isEmpty()) "new" else "ignore")
}

fun historyName(h: SyncService.History) = when (h) {
    SyncService.History.NEWEST -> "From the newest transaction already here (30 days if none)"
    SyncService.History.DAYS_30 -> "Last 30 days"
    SyncService.History.DAYS_90 -> "Last 90 days"
    SyncService.History.YEAR -> "Last year"
}

/** The bank's balance date as text, and whether the data is old enough to say so. */
fun balanceAge(epochSeconds: Long?, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Pair<String, Boolean>? {
    val d = epochSeconds?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() } ?: return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(d, today)
    return d.format(DateTimeFormatter.ofPattern("MMM d")) to (days > STALE_DAYS)
}

/** The day the provider's balance was true, in the phone's time zone. */
fun asOfDate(epochSeconds: Long?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? = epochSeconds?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }

/** Bank data older than this many days gets a warning: the provider may have stopped updating the account. */
const val STALE_DAYS = 3L

@Composable
fun StaleBankData(asOf: String) {
    Text(
        "SimpleFIN's data for this account is from $asOf. The bank may have changed since; check this connection on the " +
            "SimpleFIN Bridge site, it may need you to log in to the bank again there.",
        color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun LinkPanel(
    links: List<LinkedAccountEntity>,
    accounts: List<AccountEntity>,
    picks: Map<String, LinkPick>,
    onPick: (String, LinkPick) -> Unit,
    busy: Boolean,
    error: String?,
    today: LocalDate = LocalDate.now(),
    onApply: (() -> Unit)?,
    /** For the balance check of a provider account linked into an account that's already here. */
    budget: Budget? = null,
) {
    if (links.isEmpty()) {
        EmptyState("No accounts came back from the provider yet. Try Sync now from More, Bank sync.")
        return
    }
    var asking by remember { mutableStateOf<LinkedAccountEntity?>(null) }
    Text(
        "Choose where each account's transactions go. New ones land in To sort. Track only keeps the balance in view but out of " +
            "the budget. Accounts set to Don't sync are ignored.",
        color = DdfColors.Muted,
    )
    val targets = listOf("new" to "Create a new account", "track" to "Track only, not in the budget", "ignore" to "Don't sync this one") +
        accounts.filter { !it.archived }.map { it.id to "Into ${it.name}" }
    for (l in links) {
        val p = picks[l.id] ?: defaultPick(l, accounts)
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.providerName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (l.mask.isNotEmpty()) Pill("...${l.mask}")
                }
                l.providerBalance?.let { raw ->
                    // what the app reads: the bank's figure, with its sign flipped when the switch below says so
                    val it = if (p.actuallyNegative) -raw else raw
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bank balance ", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                        MoneyText(it, small = true)
                        balanceAge(l.providerBalanceDate, today)?.let { (d, _) -> Text(", as of $d", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                    balanceAge(l.providerBalanceDate, today)?.let { (d, stale) -> if (stale) StaleBankData(d) }
                    // linking into an account that's already here: the same balance check as the account screen
                    val into = accounts.firstOrNull { a -> a.id == p.target && !a.trackOnly }
                    if (budget != null && into != null) {
                        BalanceCheckView(BalanceCheck.check(budget, into.id, it, asOfDate(l.providerBalanceDate)), into.name, onAdjust = null)
                        if (l.lastSyncedDate == null) {
                            Text("Checked before this account's first sync: what the sync brings in can close a gap.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (p.target != "ignore" && l.providerBalance != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("It's actually negative (overdrawn)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = p.actuallyNegative, onCheckedChange = { onPick(l.id, p.copy(actuallyNegative = it)) })
                    }
                }
                Picker("Goes to", targets, p.target, { t ->
                    onPick(l.id, p.copy(target = t))
                    if (t == "ignore" && l.accountId != null) asking = l
                })
                if (p.target == "ignore" && l.accountId != null) {
                    Text(
                        if (p.removeFromBudget) "Its app account will be archived, and its balance taken off Ready to Assign."
                        else "Its app account stays in the budget, tracked by hand.",
                        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (p.target != "ignore" && l.lastSyncedDate == null) {
                    Picker("First sync starts", SyncService.History.entries.map { it to historyName(it) }, p.history, { onPick(l.id, p.copy(history = it)) })
                }
            }
        }
    }
    ErrorLine(error)
    if (onApply != null) {
        Gap()
        Button(onClick = onApply, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Syncing..." else "Link and sync") }
    }
    asking?.let { l ->
        val name = accounts.firstOrNull { it.id == l.accountId }?.name ?: "its account"
        UnlinkDialog(name, onChoice = { remove ->
            onPick(l.id, (picks[l.id] ?: defaultPick(l, accounts)).copy(target = "ignore", removeFromBudget = remove))
            asking = null
        }, onDismiss = { asking = null })
    }
}

/**
 * Asked once when a synced account stops syncing: take it off the budget (the default), or keep tracking it by
 * hand. Dismissing keeps the default.
 */
@Composable
fun UnlinkDialog(accountName: String, onChoice: (removeFromBudget: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop syncing $accountName") },
        text = {
            Column {
                Text("Remove from budget: the account is archived and its balance no longer counts in Ready to Assign. Its transactions stay as history.")
                Gap()
                Text("Keep tracking it by hand: the account stays and keeps counting in the budget. You enter its transactions yourself.", color = DdfColors.Muted)
            }
        },
        confirmButton = { TextButton(onClick = { onChoice(true) }) { Text("Remove from budget") } },
        dismissButton = { TextButton(onClick = { onChoice(false) }) { Text("Keep tracking it by hand") } },
    )
}

fun toChoices(links: List<LinkedAccountEntity>, accounts: List<AccountEntity>, picks: Map<String, LinkPick>): List<SyncService.LinkChoice> = links.map { l ->
    val p = picks[l.id] ?: defaultPick(l, accounts)
    val target = when (p.target) {
        "new" -> SyncService.Target.New(l.providerName)
        "track" -> SyncService.Target.New(l.providerName, trackOnly = true)
        "ignore" -> SyncService.Target.Ignore(p.removeFromBudget)
        else -> SyncService.Target.Existing(p.target)
    }
    SyncService.LinkChoice(l.id, target, p.history, signFlipped = p.actuallyNegative.takeIf { p.target != "ignore" })
}

/** More, Bank sync: every connection, what it last did, and the background settings. */
@Composable
fun BankSyncScreen(
    connections: List<ConnectionEntity>,
    links: List<LinkedAccountEntity>,
    accounts: List<AccountEntity>,
    hints: Map<String, String?>,
    background: Boolean,
    intervalHours: Int,
    busy: Boolean,
    message: String?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSyncNow: (String) -> Unit,
    onFix: (ConnectionEntity) -> Unit,
    onLinks: (String) -> Unit,
    onDisconnect: (id: String, removeTransactions: Boolean, removeFromBudget: Boolean) -> Unit,
    onBackground: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
) {
    var disconnecting by remember { mutableStateOf<ConnectionEntity?>(null) }
    var removeTxns by remember { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Bank sync", onBack) { TextButton(onClick = onAdd) { Text("Add") } }
        Text("Every connection runs from this phone with your own login. DarkRelay never holds your bank connection.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (connections.isEmpty()) EmptyState("No bank connections yet. Tap Add to connect through SimpleFIN.")
        message?.let { Text(it, modifier = Modifier.padding(vertical = 6.dp)) }
        for (c in connections) {
            val mine = links.filter { it.connectionId == c.id }
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                    hints[c.id]?.let { Text("Login saved ($it)", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
                    Text("Last sync: ${syncTime(c.lastSyncAt)}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    c.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (c.status == ConnectionStatus.NEEDS_FIX) DdfColors.Warning else DdfColors.Text) }
                    c.providerMessages?.let { Text("From SimpleFIN: $it", color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "${mine.count { it.accountId != null }} of ${mine.size} accounts synced" +
                            mine.filter { it.accountId != null }.joinToString("") { l -> "\n  ${l.providerName} into ${accounts.firstOrNull { it.id == l.accountId }?.name ?: "?"}" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = { onSyncNow(c.id) }, enabled = !busy) { Text("Sync now") }
                        TextButton(onClick = { onLinks(c.id) }) { Text("Accounts") }
                        if (c.status == ConnectionStatus.NEEDS_FIX) TextButton(onClick = { onFix(c) }) { Text("Fix") }
                        TextButton(onClick = { disconnecting = c }) { Text("Disconnect") }
                    }
                }
            }
        }
        SectionTitle("Background sync")
        SettingRow("Sync in the background", "Every few hours when there's a network. Nothing is shown while it runs.", background, true, onBackground)
        if (background) Picker("How often", SyncSettings.INTERVALS.map { it to "Every $it hours" }, intervalHours, onInterval)
        Text("The app also syncs when you open it (at most every 30 minutes) and when you pull down on Home or Activity.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        Gap(24)
    }
    fun pickTxns(c: ConnectionEntity, remove: Boolean) {
        val fed = links.filter { it.connectionId == c.id && it.accountId != null }
        if (fed.isEmpty()) { onDisconnect(c.id, remove, false); disconnecting = null } else removeTxns = remove
    }
    val c0 = disconnecting
    val rt = removeTxns
    if (c0 != null && rt != null) {
        val names = links.filter { it.connectionId == c0.id }.mapNotNull { l -> accounts.firstOrNull { it.id == l.accountId }?.name }.distinct()
        UnlinkDialog(names.joinToString(", ").ifEmpty { "its accounts" }, onChoice = { remove ->
            onDisconnect(c0.id, rt, remove)
            disconnecting = null
            removeTxns = null
        }, onDismiss = { disconnecting = null; removeTxns = null })
        return
    }
    disconnecting?.let { c ->
        AlertDialog(
            onDismissRequest = { disconnecting = null },
            title = { Text("Disconnect ${c.name}?") },
            text = { Text("Its saved login is deleted from this phone and it stops syncing. Keep the transactions it brought in, or remove them?") },
            confirmButton = { TextButton(onClick = { pickTxns(c, false) }) { Text("Disconnect, keep transactions") } },
            dismissButton = {
                Column {
                    TextButton(onClick = { pickTxns(c, true) }) { Text("Disconnect, remove them") }
                    TextButton(onClick = { disconnecting = null }) { Text("Cancel") }
                }
            },
        )
    }
}

/** A problem only the user can fix: shown once per session, with a button straight to the fix. */
@Composable
fun FixDialog(prompt: SyncService.FixPrompt, onFix: () -> Unit, onLater: () -> Unit) {
    val button = when (prompt.code) {
        FixCode.SIMPLEFIN_TOKEN -> "Get a new token"
        else -> "Open Bank sync"
    }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Bank sync needs you") },
        text = { Text(prompt.message) },
        confirmButton = { TextButton(onClick = onFix) { Text(button) } },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}

/** On the account screen: what feeds this account, when it last synced, and the balance check. */
@Composable
fun AccountSyncSection(
    budget: Budget,
    accountId: String,
    links: List<LinkedAccountEntity>,
    connections: List<ConnectionEntity>,
    today: LocalDate = LocalDate.now(),
    /** The balance check's Add an adjustment, after the user confirms the amount. */
    onAdjust: ((amount: Long, date: LocalDate) -> Unit)? = null,
    /** The bank's balance sign choice for a linked account (1.2.1); null hides the switch. */
    onSignFlipped: ((linkedId: String, flipped: Boolean) -> Unit)? = null,
    /** Adjust on the wrong-sign warning (1.2.2): the bank's balance and its date, to match the starting balance to. */
    onFixStart: ((bank: Long, asOf: LocalDate?) -> Unit)? = null,
    onChangeLink: (linkedId: String, accountId: String?, removeFromBudget: Boolean) -> Unit,
) {
    var asking by remember { mutableStateOf<String?>(null) }
    val mine = links.filter { it.accountId == accountId }
    SectionTitle("Bank sync")
    if (mine.isEmpty()) {
        Text("Not linked to a bank connection. Link one from More, Bank sync.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    for (l in mine) {
        val c = connections.firstOrNull { it.id == l.connectionId }
        Text("Synced from ${l.providerName}${if (l.mask.isNotEmpty()) " (...${l.mask})" else ""} through ${c?.name ?: "a connection"}")
        Text("Last sync: ${syncTime(c?.lastSyncAt)}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        val age = balanceAge(l.providerBalanceDate, today)
        val bank = l.bankBalance
        if (onSignFlipped != null && l.providerBalance != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("It's actually negative (overdrawn)")
                    Text("The bank sends this balance with the wrong sign. The app flips it every time it reads it.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = l.balanceSignFlipped, onCheckedChange = { onSignFlipped(l.id, it) })
            }
        }
        if (bank != null) {
            age?.let { (d, stale) -> if (stale) StaleBankData(d) }
            val name = budget.accounts.firstOrNull { it.id == accountId }?.name ?: "this account"
            // worked out again on every change, so flipping the sign shows the check (and its Adjust) right away
            val start = budget.accounts.firstOrNull { it.id == accountId }?.startingBalance
            BalanceCheckView(
                BalanceCheck.check(budget, accountId, bank, asOfDate(l.providerBalanceDate)), name, onAdjust,
                startingBalance = start, onFixStart = onFixStart?.let { f -> { f(bank, asOfDate(l.providerBalanceDate)) } },
            )
        }
        val targets = listOf<Pair<String?, String>>(null to "Stop syncing this account") + budget.accounts.filter { !it.archived }.map { it.id to "Into ${it.name}" }
        Picker("Link", targets, l.accountId, { if (it == null) asking = l.id else onChangeLink(l.id, it, false) })
        Gap()
    }
    asking?.let { id ->
        UnlinkDialog(budget.accounts.firstOrNull { it.id == accountId }?.name ?: "this account", onChoice = { remove ->
            onChangeLink(id, null, remove)
            asking = null
        }, onDismiss = { asking = null })
    }
}

/** Home card for installs that came from 0.6.x with accounts already in them. */
@Composable
fun FinishSetupCard(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium)
            Text("Connect your bank through SimpleFIN, or keep using CSV import. Nothing you already have is changed.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onOpen) { Text("Open setup") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}
