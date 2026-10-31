package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import net.darkrelay.deaddropfinance.serversync.ServerSync
import net.darkrelay.deaddropfinance.serversync.SyncStatus

/** The Add a phone QR code's modules (ZXing core), with the quiet zone the scanners want. */
fun qrMatrix(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))

/** Black on white whatever the theme, so a phone's camera reads it. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val m = remember(text) { qrMatrix(text) }
    Canvas(modifier.background(Color.White).testTag("qr")) {
        val cell = size.width / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

private fun pillColor(s: SyncStatus.State) = when (s) {
    SyncStatus.State.SYNCED -> C.Good
    SyncStatus.State.SYNCING -> C.Cyan
    SyncStatus.State.OFFLINE -> C.Warn
    SyncStatus.State.ERROR -> C.Bad
    SyncStatus.State.NOT_CONNECTED -> C.Dim2
}

/** The header's sync pill: the state, the last synced time and the waiting count; Sync now beside it once connected. */
@Composable
fun SyncPill(server: ServerModel?, onClick: () -> Unit) {
    val s = server?.status ?: SyncStatus(SyncStatus.State.NOT_CONNECTED)
    val shape = RoundedCornerShape(50)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.clip(shape).background(C.Panel2).border(1.dp, C.Line2, shape).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 5.dp).testTag("syncPill"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(pillColor(s.state)))
            Spacer(Modifier.width(7.dp))
            T(ServerModel.pillText(s), 12.sp, if (s.state == SyncStatus.State.NOT_CONNECTED) C.Dim else C.Text, modifier = Modifier.testTag("syncPillText"))
        }
        if (server != null && s.state != SyncStatus.State.NOT_CONNECTED) Btn("Sync now", { server.syncNow() }, ghost = true, small = true, tag = "syncNow")
    }
}

/** Read only while another device holds the edit lock, with Take over editing; and the last-write-wins notes. */
@Composable
fun SyncBanners(model: AppModel, server: ServerModel) {
    val s = server.status
    s.readOnlyBy?.let { holder ->
        Row(
            Modifier.fillMaxWidth().background(C.Warn.copy(alpha = 0.08f)).bottomLine().padding(horizontal = 18.dp, vertical = 7.dp).testTag("readOnly"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val text = if (s.takenOverBy != null) {
                "${s.takenOverBy} took over editing. This computer is read only" +
                    if (s.queued > 0) "; its ${s.queued} waiting ${if (s.queued == 1) "change is" else "changes are"} sent once it can edit again." else "."
            } else "Read only: $holder is editing the budget. Changes can be made here once it closes."
            T(text, 13.sp, C.Warn, modifier = Modifier.weight(1f).testTag("readOnlyText"))
            Btn("Take over editing", { model.dialog = Dialog.TakeOver(holder) }, small = true, tag = "takeOver")
        }
    }
    for (n in server.notices) {
        Row(
            Modifier.fillMaxWidth().background(C.Cyan.copy(alpha = 0.06f)).bottomLine().padding(horizontal = 18.dp, vertical = 7.dp).testTag("notice"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(n, 13.sp, C.Cyan, modifier = Modifier.weight(1f))
            Box(Modifier.clickable { server.sync.dismissNotice(n) }.padding(horizontal = 4.dp)) { T("✕", 12.sp, C.Dim) }
        }
    }
}

/**
 * Settings > Server: connect, check the fingerprint, then log in or create the account (0.11.0); then the connection, its
 * devices, Add a phone, Change password and Log out.
 */
@Composable
fun ServerPanel(model: AppModel, server: ServerModel?, shell: Shell) {
    Panel("Server", "Your own Dead Drop server keeps the budget in step across your devices. Everything between this computer and it is encrypted.", Modifier.fillMaxWidth().testTag("serverPanel")) {
        if (server == null) {
            T("The budget is still opening.", 13.sp, C.Dim)
            return@Panel
        }
        if (!server.connected) LoginForm(model, server, shell) else Connected(model, server, shell)
        server.error?.let { T(it, 12.sp, C.Bad, modifier = Modifier.padding(top = 10.dp).testTag("server.error")) }
    }
}

@Composable
private fun LoginForm(model: AppModel, server: ServerModel, shell: Shell) {
    val contacted = server.contacted
    if (contacted == null) {
        var address by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Server address", address, { address = it }, Modifier.weight(1f), placeholder = "address:5090", tag = "server.address")
            Btn("Connect", { val a = address; shell.run { server.connect(a) } }, enabled = address.isNotBlank() && !server.busy, tag = "server.connect")
        }
        T("The same address the phone app uses. Port 5090 is used when none is given.", 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 6.dp))
        return
    }
    var create by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(server.defaultDeviceName) }
    var stay by remember { mutableStateOf(true) }
    var localData by remember { mutableStateOf(false) }
    LaunchedEffect(contacted) { localData = server.hasLocalData() }
    Kv("Server", contacted.first)
    Kv("Key fingerprint", ServerModel.groups(contacted.second), tag = "server.fingerprint")
    T("It must match the fingerprint in the server's setup-code.txt (or its start line), or the one the phone app shows under More > Server. This computer remembers it and refuses a different one later.",
        11.5.sp, C.Dim2, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
        Btn("Log in", { create = false; server.error = null }, ghost = create, small = true, tag = "server.mode.login")
        Btn("Create the account", { create = true; server.error = null }, ghost = !create, small = true, tag = "server.mode.create")
    }
    if (create) {
        T("The first device makes the account with the server's one-time setup code. This computer's budget becomes the server's copy.", 12.sp, C.Dim, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Setup code", code, { code = it }, Modifier.weight(1f), placeholder = "XXXX-XXXX", tag = "server.setupCode")
            Field("Username", user, { user = it }, Modifier.weight(1f), tag = "server.username")
            Field("This computer's name", name, { name = it }, Modifier.weight(1f), tag = "server.name")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            Field("Password", pass, { pass = it }, Modifier.weight(1f), tag = "server.password", masked = true)
            Field("Password again", again, { again = it }, Modifier.weight(1f), tag = "server.again", masked = true)
        }
        T("At least ${ServerSync.MIN_PASSWORD} characters. The password is never kept on this computer. There is no password reset on the server: a logged-in device can set a new one under Settings > Server.",
            11.5.sp, C.Dim2, modifier = Modifier.padding(top = 6.dp))
        T("Creating the account shows the account's recovery key once. Every backup opens only with it.", 11.5.sp, C.Warn, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Create the account", {
                val c = code; val u = user; val p = pass; val a = again; val n = name
                shell.run { server.create(c, u, p, a, n) }
            }, enabled = code.isNotBlank() && user.isNotBlank() && pass.isNotEmpty() && again.isNotEmpty() && !server.busy, tag = "server.create")
            Btn("Cancel", { server.contacted = null; server.error = null }, ghost = true, tag = "server.cancel")
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Username", user, { user = it }, Modifier.weight(1f), tag = "server.username")
        Field("Password", pass, { pass = it }, Modifier.weight(1f), tag = "server.password", masked = true)
        Field("This computer's name", name, { name = it }, Modifier.weight(1f), tag = "server.name")
    }
    CheckRow("Stay logged in", "Off: closing the program logs this computer out, and the next open asks for the login again.", stay, true, "server.stay",
        Modifier.padding(top = 8.dp)) { stay = it }
    T("The password is never kept on this computer.", 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 6.dp))
    if (localData) {
        T("This computer has a budget of its own. Before the server's replaces it, a backup of it is saved to Downloads\\DeadDropFinance, encrypted with this computer's recovery key.",
            12.sp, C.Warn, modifier = Modifier.padding(top = 6.dp).testTag("server.localData"))
    }
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Log in", {
            val u = user; val p = pass; val n = name; val st = stay
            shell.run { server.startLogin(u, p, n, st) }
        }, enabled = user.isNotBlank() && pass.isNotEmpty() && !server.busy, tag = "server.login")
        Btn("Cancel", { server.contacted = null; server.error = null }, ghost = true, tag = "server.cancel")
    }
}

@Composable
private fun Connected(model: AppModel, server: ServerModel, shell: Shell) {
    val sync = server.sync
    val s = server.status
    LaunchedEffect(Unit) { server.loadDevices() }
    Kv("Server", sync.serverUrl ?: "")
    Kv("Key fingerprint", ServerModel.groups(sync.fingerprint ?: ""), tag = "server.fingerprint")
    Kv("Username", sync.username ?: "Paired with a code")
    Kv("This computer", sync.deviceName ?: "")
    Kv("Stay logged in", if (sync.stayLoggedIn) "Yes" else "No: closing the program logs out", tag = "server.stayValue")
    Kv("Status", ServerModel.pillText(s) + if (s.lastSyncedAt > 0 && s.state != SyncStatus.State.SYNCED) ", last synced ${ServerModel.clockTime(s.lastSyncedAt)}" else "",
        last = true, tag = "server.status")
    s.message?.let { T(it, 12.sp, if (s.state == SyncStatus.State.ERROR) C.Bad else C.Dim, modifier = Modifier.padding(top = 6.dp)) }
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Add a phone", { model.dialog = Dialog.AddPhone }, small = true, tag = "server.addPhone")
        Btn("Change password", { model.dialog = Dialog.ChangePassword }, ghost = true, small = true, tag = "server.changePassword")
        Btn("Log out", { model.dialog = Dialog.Logout }, ghost = true, small = true, tag = "server.logout")
    }
    Spacer(Modifier.height(16.dp))
    Label("DEVICES")
    Column(Modifier.padding(top = 6.dp)) {
        for (d in server.devices.filter { it.revokedAt == null }) {
            val me = d.id == sync.deviceId
            Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 8.dp).testTag("device.${d.name}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(d.name + if (me) " (this computer)" else "", 13.sp, weight = if (me) FontWeight.SemiBold else FontWeight.Normal)
                    val seen = d.lastSeen?.let { "last seen ${ServerModel.clockTime(it)}" } ?: "not seen yet"
                    T("${if (d.kind == "desktop") "Desktop" else "Phone"}, ${if (d.open) "open now" else seen}", 11.5.sp, C.Dim2)
                }
                if (!me) Btn("Remove", { model.dialog = Dialog.RemoveDevice(d.id, d.name) }, ghost = true, small = true, tag = "device.remove.${d.name}")
            }
        }
    }
    T("Removing a device deletes its key on the server, so it can't sync again until it logs in. Use it for a lost phone.", 11.5.sp, C.Dim2, modifier = Modifier.padding(top = 6.dp))
}

/** The server's dialogs: confirm a login that replaces this budget, Add a phone, take over, remove a device, log out, change the password. */
@Composable
fun ServerDialog(model: AppModel, server: ServerModel, shell: Shell, d: Dialog) {
    val close = { model.dialog = null }
    when (d) {
        is Dialog.ConfirmLogin -> Modal("Replace this computer's budget?", 470.dp, close, "dialog.confirmLogin", footer = {
            Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
            Btn("Back up and log in", { close(); shell.run { server.login(d.username, d.pass, d.name, d.stay) } }, tag = "dialog.confirm")
        }) {
            T("If the server has a budget, it replaces the one on this computer. A backup of this computer's budget is saved first, to Downloads\\DeadDropFinance, encrypted with this computer's recovery key.", 13.sp)
        }
        is Dialog.ChangePassword -> ChangePasswordDialog(server, shell, close)
        is Dialog.TakeOver -> Modal("Take over editing?", 470.dp, close, "dialog.takeOver", footer = {
            Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
            Btn("Take over", { close(); shell.run { server.takeOver() } }, tag = "dialog.confirm")
        }) {
            T("${d.holder} is editing the budget. Taking over moves the edit lock to this computer.", 13.sp)
            T("${d.holder} turns read only. Changes it hasn't sent yet wait there and are sent once it can edit again (the last change wins).", 12.sp, C.Dim)
        }
        is Dialog.RemoveDevice -> Modal("Remove ${d.name}?", 470.dp, close, "dialog.removeDevice", footer = {
            Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
            Btn("Remove", { close(); shell.run { server.revoke(d.id) } }, tag = "dialog.confirm")
        }) {
            T("Its key is deleted on the server, so it can't sync again until it logs in. Its own budget stays on it.", 13.sp)
        }
        is Dialog.Logout -> Modal("Log out of the server?", 470.dp, close, "dialog.logout", footer = {
            Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
            Btn("Log out", { close(); shell.run { server.logout() } }, tag = "dialog.confirm")
        }) {
            val q = server.status.queued
            T(if (q > 0) "$q ${if (q == 1) "change hasn't" else "changes haven't"} reached the server. They're sent first if it can be reached; otherwise they stay only on this computer."
              else "This computer stops syncing. Its budget stays here.", 13.sp)
            T("It lets go of the edit lock and removes this computer from the server's devices.", 12.sp, C.Dim)
        }
        is Dialog.AddPhone -> AddPhoneDialog(server, shell, close)
        else -> {}
    }
}

@Composable
private fun ChangePasswordDialog(server: ServerModel, shell: Shell, close: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    Modal("Change the account password", 470.dp, close, "dialog.changePassword", footer = {
        Btn("Cancel", close, ghost = true, tag = "dialog.cancel")
        Btn("Change password", { val p = pass; val a = again; shell.run { if (server.changePassword(p, a)) close() } },
            enabled = pass.isNotEmpty() && again.isNotEmpty() && !server.busy, tag = "dialog.confirm")
    }) {
        T("This computer is logged in, so the old password isn't needed. Your other devices stay logged in; the new password is for the next login.", 13.sp, C.Dim)
        Field("New password", pass, { pass = it }, tag = "pw.new", masked = true)
        Field("New password again", again, { again = it }, tag = "pw.again", masked = true)
        T("At least ${ServerSync.MIN_PASSWORD} characters. There is no password reset on the server, so keep it somewhere safe.", 11.5.sp, C.Dim2)
        server.error?.let { T(it, 12.sp, C.Bad, modifier = Modifier.testTag("pw.error")) }
    }
}

@Composable
private fun AddPhoneDialog(server: ServerModel, shell: Shell, close: () -> Unit) {
    val done = { server.closePairing(); close() }
    LaunchedEffect(Unit) { if (server.pairing == null) server.newPairingCode() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    Modal("Add a phone", 470.dp, done, "dialog.addPhone", footer = {
        Btn("New code", { shell.run { server.newPairingCode() } }, ghost = true, enabled = !server.busy, tag = "pair.new")
        Btn("Close", done, tag = "dialog.cancel")
    }) {
        T("On the phone: More > Server > Scan QR code. The phone checks the server's key against this code's fingerprint, then gets its own key.", 13.sp, C.Dim)
        val p = server.pairing
        if (p == null) {
            T(if (server.busy) "Asking the server for a code..." else (server.error ?: ""), 13.sp, if (server.error != null) C.Bad else C.Dim, modifier = Modifier.testTag("pair.waiting"))
            return@Modal
        }
        val left = p.expiresAt - now
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            QrImage(p.qr, Modifier.size(240.dp).alpha(if (left > 0) 1f else 0.15f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T(p.code.code, 18.sp, weight = FontWeight.Bold, spacing = 1.5.sp, modifier = Modifier.testTag("pair.code"))
            T(if (left > 0) "Expires in %d:%02d".format(left / 60_000, left / 1000 % 60) else "This code has expired. Make a new one.",
                13.sp, if (left > 0) C.Dim else C.Bad, modifier = Modifier.testTag("pair.expiry"))
        }
        T("The code works once, for 10 minutes. The QR code holds this server's address, its key fingerprint and the code, never your password or a key.",
            11.5.sp, C.Dim2, modifier = Modifier.padding(top = 6.dp))
    }
}
