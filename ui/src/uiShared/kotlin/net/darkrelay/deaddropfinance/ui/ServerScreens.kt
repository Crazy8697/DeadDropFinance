package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import net.darkrelay.deaddropfinance.serversync.PairingQr
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
    /** A scanned pairing QR code (1.5.0): the desktop program's Add a phone. */
    val scanned: PairingQr.Info? = null,
    /** Why the scan didn't happen (the camera was refused), shown above the typed fields. */
    val scanNote: String? = null,
)

/** What came back from Scan QR code (1.5.0). */
sealed class QrScan {
    data class Read(val text: String) : QrScan()
    object CameraDenied : QrScan()
    object Cancelled : QrScan()
}

/** The note shown when the camera is refused: the typed fields still work. */
const val CAMERA_DENIED_NOTE = "Without the camera, the QR code can't be scanned. Type the server's address below instead, or allow the camera for Dead Drop Finance in Android's settings."

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

/** The read-only banner's words: who is editing, or who took over from this phone and what waits here. */
fun readOnlyText(s: SyncStatus): String = when {
    s.takenOverBy != null -> "${s.takenOverBy} took over editing. This phone is read only" +
        if (s.queued > 0) "; its ${s.queued} waiting ${if (s.queued == 1) "change is" else "changes are"} sent once it can edit again." else "."
    else -> "Read only: ${s.readOnlyBy} is editing the budget. You can make changes here once it closes."
}

/**
 * Shown on every screen while another device holds the edit lock, with Take over editing (1.5.0): a confirmation
 * naming that device, then the lock moves to this phone and that device turns read only.
 */
@Composable
fun ReadOnlyBanner(status: SyncStatus, onTakeOver: () -> Unit) {
    val holder = status.readOnlyBy ?: return
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(DdfColors.Cyan).padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(readOnlyText(status), color = DdfColors.Background, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { confirming = true }) { Text("Take over editing", color = DdfColors.Background) }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Take over editing?") },
            text = {
                Text("$holder is editing the budget. Taking over moves the edit lock to this phone: $holder turns read only, and changes it hasn't sent yet wait there until it can edit again (the last change wins).")
            },
            confirmButton = { TextButton(onClick = { confirming = false; onTakeOver() }) { Text("Take over") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
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
    /** [stay]: Stay logged in (1.6.0), on by default; off logs out when the app closes. */
    onLogin: (username: String, pass: String, deviceName: String, stay: Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onRevoke: (DeviceInfo) -> Unit,
    onLogout: () -> Unit,
    /** Scan QR code (1.5.0); null where there is no camera to ask for. */
    onScan: (() -> Unit)? = null,
    /** Pair from the scanned code: this device's name (a budget of its own is backed up first, under its recovery key). */
    onPair: (deviceName: String) -> Unit = {},
    onCancelScan: () -> Unit = {},
    /** Password change (1.6.0): a new password for the account, from this logged-in phone. */
    onChangePassword: (pass: String) -> Unit = {},
    /** Stay logged in, as chosen at login (1.6.0). */
    stayLoggedIn: Boolean = true,
) {
    val busy = ui.working != null
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Server", onBack)
        if (ui.working != null) Text(ui.working, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 8.dp))
        if (ui.message != null) Text(ui.message, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 8.dp))
        ErrorLine(ui.error)
        if (connected) Connected(ui, status, info, busy, onSyncNow, onRevoke, onLogout, onChangePassword, stayLoggedIn)
        else if (ui.scanned != null) Scanned(ui, ui.scanned, busy, onPair, onCancelScan)
        else Connect(ui, busy, onContact, onMode, onCreate, onLogin, onScan)
        Gap(24)
    }
}

/** A scanned Add a phone code: the server it names, then this phone's name. */
@Composable
private fun Scanned(ui: ServerUi, qr: PairingQr.Info, busy: Boolean, onPair: (String) -> Unit, onCancel: () -> Unit) {
    var deviceName by remember { mutableStateOf("Phone") }
    var tried by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    SectionTitle("Pair with the scanned code")
    Text(
        "From the desktop program's Add a phone. The phone checks the server's key against this fingerprint, then gets its own key with the one-time code. The server's budget replaces the one on this phone.",
        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
    )
    Line("Address", qr.serverUrl)
    Line("Fingerprint", fingerprintText(qr.fingerprint))
    Field("This device's name", deviceName, KeyboardType.Text) { deviceName = it }
    if (ui.hasLocalData) {
        Text(
            "This phone has a budget of its own. Before the server's replaces it, a backup of it is saved to Downloads/DeadDropFinance, encrypted with this phone's recovery key (made and shown first if it has none).",
            color = DdfColors.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
        )
    }
    val problem = if (deviceName.isBlank()) "Name this device." else null
    if (tried && problem != null) Text(problem, color = DdfColors.Error, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.padding(top = 8.dp)) {
        Button(onClick = {
            tried = true
            if (problem != null) return@Button
            if (ui.hasLocalData) confirmReplace = true else onPair(deviceName)
        }, enabled = !busy) { Text("Pair this phone") }
        TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace this phone's budget?") },
            text = { Text("If the server has a budget, it replaces the one on this phone. A backup of this phone's budget is saved first, to Downloads/DeadDropFinance, encrypted with this phone's recovery key.") },
            confirmButton = { TextButton(onClick = { confirmReplace = false; onPair(deviceName) }) { Text("Back up and pair", color = DdfColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Connect(
    ui: ServerUi, busy: Boolean, onContact: (String) -> Unit, onMode: (Boolean) -> Unit,
    onCreate: (String, String, String, String) -> Unit, onLogin: (String, String, String, Boolean) -> Unit, onScan: (() -> Unit)?,
) {
    var stay by remember { mutableStateOf(true) }
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
    if (onScan != null) {
        Text("With the desktop program logged in, its Settings > Server > Add a phone shows a QR code that does all of this.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onScan, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) { Text("Scan QR code") }
    }
    ui.scanNote?.let { Text(it, color = DdfColors.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
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
    if (!ui.createMode) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp)
                .toggleable(value = stay, enabled = !busy, role = androidx.compose.ui.semantics.Role.Checkbox) { stay = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Checkbox(checked = stay, onCheckedChange = null, enabled = !busy)
            Column(Modifier.weight(1f)) {
                Text("Stay logged in")
                Text("Off: closing the app logs this phone out, and the next open asks for the login again.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        Text("Creating the account shows the account's recovery key once. Every backup opens only with it. There is no password reset on the server: a logged-in device can set a new one here.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
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
            "This phone has a budget of its own. Before the server's replaces it, a backup of it is saved to Downloads/DeadDropFinance, encrypted with this phone's recovery key (made and shown first if it has none).",
            color = DdfColors.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
        )
    }
    Button(
        onClick = {
            tried = true
            if (problem != null) return@Button
            if (ui.createMode) onCreate(code, username, password, deviceName)
            else if (ui.hasLocalData) confirmReplace = true
            else onLogin(username, password, deviceName, stay)
        },
        enabled = !busy, modifier = Modifier.padding(top = 8.dp),
    ) { Text(if (ui.createMode) "Create account" else "Log in") }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace this phone's budget?") },
            text = { Text("If the server has a budget, it replaces the one on this phone. A backup of this phone's budget is saved first, to Downloads/DeadDropFinance, encrypted with this phone's recovery key.") },
            confirmButton = { TextButton(onClick = { confirmReplace = false; onLogin(username, password, deviceName, stay) }) { Text("Back up and log in", color = DdfColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Connected(
    ui: ServerUi, status: SyncStatus, info: ServerInfo, busy: Boolean,
    onSyncNow: () -> Unit, onRevoke: (DeviceInfo) -> Unit, onLogout: () -> Unit, onChangePassword: (String) -> Unit, stayLoggedIn: Boolean,
) {
    var revoking by remember { mutableStateOf<DeviceInfo?>(null) }
    var loggingOut by remember { mutableStateOf(false) }
    var newPass by remember { mutableStateOf("") }
    var newAgain by remember { mutableStateOf("") }
    var triedPass by remember { mutableStateOf(false) }
    SyncStatusLine(status, onSyncNow, Modifier.padding(vertical = 4.dp))
    if (status.readOnlyBy != null) Text(readOnlyText(status), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    SectionTitle("This server")
    Line("Address", info.url ?: "")
    info.fingerprint?.let { Line("Fingerprint", fingerprintText(it)) }
    Line("Account", info.username ?: "Paired with a code")
    Line("This device", info.deviceName ?: "")
    Line("Stay logged in", if (stayLoggedIn) "Yes" else "No: closing the app logs out")
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
    SectionTitle("Password")
    Text("This phone is logged in, so the old password isn't needed. Other devices stay logged in; the new password is for the next login. There is no password reset on the server.",
        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    Secret("New password", newPass) { newPass = it }
    Secret("New password again", newAgain) { newAgain = it }
    val passProblem = serverPasswordProblem(newPass, newAgain)
    if (triedPass && passProblem != null) Text(passProblem, color = DdfColors.Error, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        triedPass = true
        if (passProblem == null) { onChangePassword(newPass); newPass = ""; newAgain = ""; triedPass = false }
    }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Change password") }
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
