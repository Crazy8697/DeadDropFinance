package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.serversync.DeviceInfo
import net.darkrelay.deaddropfinance.serversync.SyncStatus

/** What the Server screen shows besides the sync status (1.4.0). */
data class ServerUi(
    /** The address checked with Connect, and its key's fingerprint, before the account details go. */
    val contactedUrl: String? = null,
    val contactedFingerprint: String? = null,
    val createMode: Boolean = true,
    val devices: List<DeviceInfo>? = null,
    val hasLocalData: Boolean = false,
    val working: String? = null,
    val message: String? = null,
    val error: String? = null,
)

/** The connected server, as kept on the phone (nothing secret). */
data class ServerInfo(val url: String?, val fingerprint: String?, val username: String?, val deviceName: String?, val deviceId: String?)

/** A password for the server account has to be at least this long (the server's rule too). */
const val SERVER_PASSWORD_MIN = 8

/** Why a new account password can't be used, or null when it can. */
fun serverPasswordProblem(pass: String, again: String): String? = when {
    pass.length < SERVER_PASSWORD_MIN -> "Use at least $SERVER_PASSWORD_MIN characters."
    pass != again -> "The two passwords don't match."
    else -> null
}

/** "1a2b3c4d5e6f7a8b" as "1a2b 3c4d 5e6f 7a8b", easier to compare by eye. */
fun fingerprintText(fp: String) = fp.chunked(4).joinToString(" ")

/** The status in words: Not connected, Syncing, Synced, Offline (with what's waiting), or the error. */
fun syncStatusText(s: SyncStatus): String = when (s.state) {
    SyncStatus.State.NOT_CONNECTED -> "Not connected"
    SyncStatus.State.SYNCING -> "Syncing"
    SyncStatus.State.SYNCED -> "Synced"
    SyncStatus.State.OFFLINE -> if (s.queued > 0) "Offline, ${s.queued} ${if (s.queued == 1) "change" else "changes"} waiting" else "Offline"
    SyncStatus.State.ERROR -> "Sync error"
}

/** The status line (Home and the Server screen): the state, when it last synced, and Sync Now. */
@Composable
fun SyncStatusLine(s: SyncStatus, onSyncNow: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                syncStatusText(s),
                color = when (s.state) {
                    SyncStatus.State.ERROR -> DdfColors.Error
                    SyncStatus.State.SYNCED -> DdfColors.Cyan
                    else -> DdfColors.Muted
                },
            )
            val detail = buildList {
                if (s.lastSyncedAt > 0) add("Synced ${whenText(s.lastSyncedAt)}")
                if (s.state != SyncStatus.State.OFFLINE && s.queued > 0) add("${s.queued} waiting")
                if (s.state == SyncStatus.State.ERROR && s.message != null) add(s.message)
            }
            if (detail.isNotEmpty()) Text(detail.joinToString(". "), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (s.state != SyncStatus.State.NOT_CONNECTED) TextButton(onClick = onSyncNow, enabled = s.state != SyncStatus.State.SYNCING) { Text("Sync Now") }
    }
}

/** Shown on every screen while another device holds the edit lock. */
@Composable
fun ReadOnlyBanner(holder: String) {
    Text(
        "Read only: $holder is editing the budget. You can make changes here once it closes.",
        color = DdfColors.Background, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().background(DdfColors.Cyan).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** A last-write-wins note: one of this phone's changes was replaced by a newer one. */
@Composable
fun SyncNotice(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

/**
 * Settings > Server (1.4.0). Not connected: Connect to server (the address first, its fingerprint to check, then
 * create the account with the setup code, or log in). Connected: the status with Sync Now, the server, the device
 * list with revoke, and Log out.
 */
@Composable
fun ServerScreen(
    ui: ServerUi,
    status: SyncStatus,
    info: ServerInfo,
    connected: Boolean,
    onBack: () -> Unit,
    onContact: (address: String) -> Unit,
    onMode: (create: Boolean) -> Unit,
    onCreate: (setupCode: String, username: String, pass: String, deviceName: String) -> Unit,
    onLogin: (username: String, pass: String, deviceName: String) -> Unit,
    onSyncNow: () -> Unit,
    onRevoke: (DeviceInfo) -> Unit,
    onLogout: () -> Unit,
) {
    val busy = ui.working != null
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Server", onBack)
        if (ui.working != null) Text(ui.working, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 8.dp))
        if (ui.message != null) Text(ui.message, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 8.dp))
        ErrorLine(ui.error)
        if (connected) Connected(ui, status, info, busy, onSyncNow, onRevoke, onLogout)
        else Connect(ui, busy, onContact, onMode, onCreate, onLogin)
        Gap(24)
    }
}

@Composable
private fun Connect(
    ui: ServerUi, busy: Boolean, onContact: (String) -> Unit, onMode: (Boolean) -> Unit,
    onCreate: (String, String, String, String) -> Unit, onLogin: (String, String, String) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Phone") }
    var tried by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    SectionTitle("Connect to server")
    Text(
        "Your own Dead Drop server keeps the budget in step across your devices. Everything between this phone and it is encrypted.",
        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
    )
    Field("Server address", address, KeyboardType.Uri) { address = it }
    Text("The server's IP address or name, with :5090 (its port) unless it uses another.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { onContact(address) }, enabled = !busy && address.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Connect") }
    val fp = ui.contactedFingerprint ?: return
    SectionTitle("Check the server's fingerprint")
    Text(fingerprintText(fp), style = MaterialTheme.typography.titleMedium, color = DdfColors.Cyan)
    Text(
        "It must match the fingerprint in the server's setup-code.txt (or its start line). This phone remembers it and refuses a different one later.",
        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.padding(top = 8.dp)) {
        TextButton(onClick = { onMode(true) }, enabled = !busy) { Text("Create the account", color = if (ui.createMode) DdfColors.Cyan else DdfColors.Muted) }
        TextButton(onClick = { onMode(false) }, enabled = !busy) { Text("Log in", color = if (!ui.createMode) DdfColors.Cyan else DdfColors.Muted) }
    }
    if (ui.createMode) {
        Text("The first device makes the account with the server's one-time setup code. This phone's budget becomes the server's copy.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        Field("Setup code", code, KeyboardType.Ascii) { code = it }
    } else {
        Text("Log in with the account's username and password. The server's budget replaces the one on this phone.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
    Field("Username", username, KeyboardType.Ascii) { username = it }
    Secret("Password", password) { password = it }
    if (ui.createMode) Secret("Password again", again) { again = it }
    Field("This device's name", deviceName, KeyboardType.Text) { deviceName = it }
    val problem = when {
        username.isBlank() -> "Type a username."
        deviceName.isBlank() -> "Name this device."
        ui.createMode && code.isBlank() -> "Type the setup code."
        ui.createMode -> serverPasswordProblem(password, again)
        password.isEmpty() -> "Type the password."
        else -> null
    }
    Text(
        if (tried && problem != null) problem else "The password is never kept on this phone.",
        color = if (tried && problem != null) DdfColors.Error else DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
    )
    if (!ui.createMode && ui.hasLocalData) {
        Text(
            "This phone has a budget of its own. Before the server's replaces it, a backup of it is saved to Downloads/DeadDropFinance, encrypted with the account password.",
            color = DdfColors.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
        )
    }
    Button(
        onClick = {
            tried = true
            if (problem != null) return@Button
            if (ui.createMode) onCreate(code, username, password, deviceName)
            else if (ui.hasLocalData) confirmReplace = true
            else onLogin(username, password, deviceName)
        },
        enabled = !busy, modifier = Modifier.padding(top = 8.dp),
    ) { Text(if (ui.createMode) "Create account" else "Log in") }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace this phone's budget?") },
            text = { Text("If the server has a budget, it replaces the one on this phone. A backup of this phone's budget is saved first, to Downloads/DeadDropFinance, encrypted with the account password.") },
            confirmButton = { TextButton(onClick = { confirmReplace = false; onLogin(username, password, deviceName) }) { Text("Back up and log in", color = DdfColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Connected(
    ui: ServerUi, status: SyncStatus, info: ServerInfo, busy: Boolean,
    onSyncNow: () -> Unit, onRevoke: (DeviceInfo) -> Unit, onLogout: () -> Unit,
) {
    var revoking by remember { mutableStateOf<DeviceInfo?>(null) }
    var loggingOut by remember { mutableStateOf(false) }
    SyncStatusLine(status, onSyncNow, Modifier.padding(vertical = 4.dp))
    if (status.readOnlyBy != null) Text("Read only: ${status.readOnlyBy} holds the edit lock.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    SectionTitle("This server")
    Line("Address", info.url ?: "")
    info.fingerprint?.let { Line("Fingerprint", fingerprintText(it)) }
    Line("Account", info.username ?: "")
    Line("This device", info.deviceName ?: "")
    SectionTitle("Devices")
    val devices = ui.devices
    if (devices == null) Text("Shown once the server can be reached.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    else for (d in devices) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name + if (d.id == info.deviceId) " (this phone)" else "")
                val state = when {
                    d.revokedAt != null -> "Removed ${whenText(d.revokedAt)}"
                    d.open -> "Open now"
                    d.lastSeen != null -> "Last seen ${whenText(d.lastSeen)}"
                    else -> "Not seen yet"
                }
                Text("${if (d.kind == "desktop") "Computer" else "Phone"}. $state", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (d.revokedAt == null && d.id != info.deviceId) TextButton(onClick = { revoking = d }, enabled = !busy) { Text("Remove", color = DdfColors.Error) }
        }
    }
    SectionTitle("Log out")
    Text("Lets go of the edit lock and deletes this phone's server key. The budget stays on this phone.",
        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { loggingOut = true }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Log out", color = DdfColors.Error) }
    revoking?.let { d ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Remove ${d.name}?") },
            text = { Text("Its key is deleted on the server, so it can't sync again until it logs in.") },
            confirmButton = { TextButton(onClick = { revoking = null; onRevoke(d) }) { Text("Remove", color = DdfColors.Error) } },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
    if (loggingOut) {
        AlertDialog(
            onDismissRequest = { loggingOut = false },
            title = { Text("Log out of the server?") },
            text = {
                Text(
                    if (status.queued > 0) "${status.queued} ${if (status.queued == 1) "change" else "changes"} haven't reached the server. They're sent first if it can be reached; otherwise they stay only on this phone."
                    else "This phone stops syncing. Its budget stays here.",
                )
            },
            confirmButton = { TextButton(onClick = { loggingOut = false; onLogout() }) { Text("Log out", color = DdfColors.Error) } },
            dismissButton = { TextButton(onClick = { loggingOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = DdfColors.Muted, modifier = Modifier.weight(0.4f))
        Text(value, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun Field(label: String, text: String, type: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Secret(label: String, text: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}
