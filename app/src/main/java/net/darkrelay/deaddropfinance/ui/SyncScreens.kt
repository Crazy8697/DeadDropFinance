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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.ConnectionEntity
import net.darkrelay.deaddropfinance.data.ConnectionStatus
import net.darkrelay.deaddropfinance.data.LinkedAccountEntity
import net.darkrelay.deaddropfinance.data.Provider
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.sync.FixCode
import net.darkrelay.deaddropfinance.sync.SyncService
import net.darkrelay.deaddropfinance.sync.SyncSettings

/** The three ways in (FEATURES.md and ROADMAP.md Stage 7). */
enum class Door { NERDY, EASY, OLD_SCHOOL }

/** Links the door screens open in the real browser. */
object ProviderLinks {
    const val TELLER_SIGN_IN = "https://teller.io/session"
    const val TELLER_APPLICATION = "https://teller.io/settings/application"
    const val TELLER_CERTIFICATES = "https://teller.io/settings/certificates"
    const val TELLER_DOCS = "https://teller.io/docs"
    const val SIMPLEFIN_BRIDGE = "https://beta-bridge.simplefin.org/"
    const val SIMPLEFIN_NEW_TOKEN = "https://beta-bridge.simplefin.org/simplefin/create"
}

/** What the door screens hold while the user fills them in (typed text lives only in memory). */
class ConnectUi {
    var appId by mutableStateOf("")
    var appIdMessage by mutableStateOf<String?>(null)
    var environment by mutableStateOf("development")
    var certMessage by mutableStateOf<String?>(null)
    var token by mutableStateOf("")
    var tokenMessage by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** A certificate or key picked so far, held in memory only until both are there and saved. */
    var pendingCertificate: String? = null
    var pendingKey: String? = null

    fun clearSecrets() { token = ""; pendingCertificate = null; pendingKey = null }
}

private val whenFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a")
fun syncTime(at: Long?, zone: ZoneId = ZoneId.systemDefault()) = at?.let { Instant.ofEpochMilli(it).atZone(zone).format(whenFormat) } ?: "never"

@Composable
fun DoorList(onPick: (Door) -> Unit, includeOldSchool: Boolean = true) {
    DoorCard("Nerdy: Teller", "Your own free Teller developer account (its free tier allows up to 100 bank logins). The most steps to set up.", "Costs nothing.") { onPick(Door.NERDY) }
    DoorCard("Easy: SimpleFIN", "Sign up with SimpleFIN Bridge, connect your bank there, paste one token here.", "SimpleFIN charges you a fee, paid to SimpleFIN.") { onPick(Door.EASY) }
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

/** Nerdy door: the user's own Teller developer account, certificate and application ID. */
@Composable
fun TellerDoor(
    ui: ConnectUi,
    certificateSaved: Boolean,
    certificateExpires: String?,
    onOpen: (String) -> Unit,
    onImportFiles: () -> Unit,
    onConnect: () -> Unit,
) {
    Text(
        "Teller connects to your bank. You use your own free Teller developer account, so your bank login goes from this phone to Teller " +
            "and nowhere else. DarkRelay never sees it.",
        color = DdfColors.Muted,
    )
    Gap()
    Text("1. Sign up or sign in at Teller. Signing up downloads a file called teller.zip with your certificate and private key.")
    Row { TextButton(onClick = { onOpen(ProviderLinks.TELLER_SIGN_IN) }) { Text("Open Teller") }; TextButton(onClick = { onOpen(ProviderLinks.TELLER_CERTIFICATES) }) { Text("Certificates page") } }
    Text("2. Import teller.zip, or the certificate and private key files from it.")
    OutlinedButton(onClick = onImportFiles) { Text(if (certificateSaved) "Import again" else "Import teller.zip or the .pem files") }
    if (certificateSaved) Text("Certificate saved on this phone" + (certificateExpires?.let { ", valid until $it" } ?: "") + ".", color = DdfColors.Cyan, style = MaterialTheme.typography.bodySmall)
    ui.certMessage?.let { Text(it, color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall) }
    Gap()
    Text("3. Copy your application ID (it starts with app_) from the Application page and paste it here.")
    TextButton(onClick = { onOpen(ProviderLinks.TELLER_APPLICATION) }) { Text("Application page") }
    OutlinedTextField(value = ui.appId, onValueChange = { ui.appId = it; ui.appIdMessage = null }, label = { Text("Application ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    ui.appIdMessage?.let { Text(it, color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall) }
    Picker(
        "Environment", listOf("development" to "Development (real banks, free, up to 100 logins)", "production" to "Production (needs Teller's business approval)", "sandbox" to "Sandbox (test data only)"),
        ui.environment, { ui.environment = it },
    )
    Gap()
    Text("4. Log in to your bank inside Teller's own page.")
    ErrorLine(ui.error)
    Button(onClick = onConnect, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text(if (ui.busy) "Working..." else "Connect a bank") }
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

/** One provider account's link choice: "new", "ignore" or an app account id. */
data class LinkPick(val target: String, val history: SyncService.History = SyncService.History.NEWEST)

fun defaultPick(l: LinkedAccountEntity, accounts: List<AccountEntity>): LinkPick {
    val open = accounts.filter { !it.archived }
    l.accountId?.let { id -> if (open.any { it.id == id }) return LinkPick(id) }
    open.firstOrNull { it.name.equals(l.providerName, ignoreCase = true) }?.let { return LinkPick(it.id) }
    return LinkPick(if (open.isEmpty()) "new" else "ignore")
}

fun historyName(h: SyncService.History) = when (h) {
    SyncService.History.NEWEST -> "From the newest transaction already here (30 days if none)"
    SyncService.History.DAYS_30 -> "Last 30 days"
    SyncService.History.DAYS_90 -> "Last 90 days"
    SyncService.History.YEAR -> "Last year"
}

@Composable
fun LinkPanel(
    links: List<LinkedAccountEntity>,
    accounts: List<AccountEntity>,
    picks: Map<String, LinkPick>,
    onPick: (String, LinkPick) -> Unit,
    busy: Boolean,
    error: String?,
    onApply: (() -> Unit)?,
) {
    if (links.isEmpty()) {
        EmptyState("No accounts came back from the provider yet. Try Sync now from More, Bank sync.")
        return
    }
    Text("Choose where each account's transactions go. New ones land in To sort. Accounts set to Don't sync are ignored.", color = DdfColors.Muted)
    val targets = listOf("new" to "Create a new account", "ignore" to "Don't sync this one") + accounts.filter { !it.archived }.map { it.id to "Into ${it.name}" }
    for (l in links) {
        val p = picks[l.id] ?: defaultPick(l, accounts)
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.providerName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (l.mask.isNotEmpty()) Pill("...${l.mask}")
                }
                l.providerBalance?.let { Text("Bank balance ${money(it)}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
                Picker("Goes to", targets, p.target, { onPick(l.id, p.copy(target = it)) })
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
}

fun toChoices(links: List<LinkedAccountEntity>, accounts: List<AccountEntity>, picks: Map<String, LinkPick>): List<SyncService.LinkChoice> = links.map { l ->
    val p = picks[l.id] ?: defaultPick(l, accounts)
    val target = when (p.target) {
        "new" -> SyncService.Target.New(l.providerName)
        "ignore" -> SyncService.Target.Ignore
        else -> SyncService.Target.Existing(p.target)
    }
    SyncService.LinkChoice(l.id, target, p.history)
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
    onDisconnect: (String, Boolean) -> Unit,
    onBackground: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
) {
    var disconnecting by remember { mutableStateOf<ConnectionEntity?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Bank sync", onBack) { TextButton(onClick = onAdd) { Text("Add") } }
        Text("Every connection runs from this phone with your own login. DarkRelay never holds your bank connection.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (connections.isEmpty()) EmptyState("No bank connections yet. Tap Add to connect through Teller or SimpleFIN.")
        message?.let { Text(it, modifier = Modifier.padding(vertical = 6.dp)) }
        for (c in connections) {
            val mine = links.filter { it.connectionId == c.id }
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Pill(if (c.provider == Provider.TELLER) "Teller" else "SimpleFIN")
                    }
                    hints[c.id]?.let { Text("Login saved ($it)", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
                    Text("Last sync: ${syncTime(c.lastSyncAt)}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    c.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (c.status == ConnectionStatus.NEEDS_FIX) DdfColors.Warning else DdfColors.Text) }
                    c.providerMessages?.let { Text("From ${if (c.provider == Provider.SIMPLEFIN) "SimpleFIN" else "Teller"}: $it", color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall) }
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
    disconnecting?.let { c ->
        AlertDialog(
            onDismissRequest = { disconnecting = null },
            title = { Text("Disconnect ${c.name}?") },
            text = { Text("Its saved login is deleted from this phone and it stops syncing. Keep the transactions it brought in, or remove them?") },
            confirmButton = { TextButton(onClick = { onDisconnect(c.id, false); disconnecting = null }) { Text("Disconnect, keep transactions") } },
            dismissButton = {
                Column {
                    TextButton(onClick = { onDisconnect(c.id, true); disconnecting = null }) { Text("Disconnect, remove them") }
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
        FixCode.TELLER_CERTIFICATE -> "Re-import certificate"
        FixCode.TELLER_RECONNECT -> "Reconnect"
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

/** Posted balance of an app account: pending rows left out, since the bank's balance leaves them out too. */
fun postedBalance(b: Budget, accountId: String): Long {
    val a = b.accounts.firstOrNull { it.id == accountId } ?: return 0
    var bal = a.startingBalance
    for (t in b.transactions) {
        when {
            t.kind == TransactionKind.TRANSFER && t.accountId == accountId -> bal -= t.amount
            t.kind == TransactionKind.TRANSFER && t.transferAccountId == accountId -> bal += t.amount
            t.kind == TransactionKind.NORMAL && t.accountId == accountId && !t.pending -> bal += t.amount
        }
    }
    return bal
}

/** On the account screen: what feeds this account, when it last synced, and the balance check. */
@Composable
fun AccountSyncSection(
    budget: Budget,
    accountId: String,
    links: List<LinkedAccountEntity>,
    connections: List<ConnectionEntity>,
    onChangeLink: (linkedId: String, accountId: String?) -> Unit,
) {
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
        val bank = l.providerBalance
        if (bank != null) {
            val app = postedBalance(budget, accountId)
            val diff = app - bank
            if (diff == 0L) {
                Text("Balance check: matches the bank (${money(bank)}).", color = DdfColors.Cyan, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Balance check: the bank says ${money(bank)}, the app says ${money(app)}. Off by ${money(kotlin.math.abs(diff))}.", color = DdfColors.Warning)
                Text(
                    "Pending transactions aren't counted on either side. A difference usually means a transaction from before the first sync, one entered " +
                        "by hand that the bank shows differently, or a starting balance that's off. Nothing is adjusted for you.",
                    color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val targets = listOf<Pair<String?, String>>(null to "Stop syncing this account") + budget.accounts.filter { !it.archived }.map { it.id to "Into ${it.name}" }
        Picker("Link", targets, l.accountId, { onChangeLink(l.id, it) })
        Gap()
    }
}

/** Home card for installs that came from 0.6.x with accounts already in them. */
@Composable
fun FinishSetupCard(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium)
            Text("Connect your bank (Teller or SimpleFIN), or keep using CSV import. Nothing you already have is changed.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onOpen) { Text("Open setup") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}
