package net.darkrelay.deaddropfinance.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.darkrelay.deaddropfinance.data.Dpapi
import net.darkrelay.deaddropfinance.data.KeyProtector
import net.darkrelay.deaddropfinance.serversync.DeviceInfo
import net.darkrelay.deaddropfinance.serversync.KeyVault
import net.darkrelay.deaddropfinance.serversync.PairingCode
import net.darkrelay.deaddropfinance.serversync.ServerClient
import net.darkrelay.deaddropfinance.serversync.ServerException
import net.darkrelay.deaddropfinance.serversync.ServerSync
import net.darkrelay.deaddropfinance.serversync.SyncPrefs
import net.darkrelay.deaddropfinance.serversync.SyncStatus
import net.darkrelay.deaddropfinance.serversync.hex
import net.darkrelay.deaddropfinance.serversync.unhex

/**
 * The program's server connection settings (0.10.0), in %APPDATA%\DeadDropFinance\server.properties. Nothing here is
 * secret: the address, the pinned public keys, this computer's id on the server, its name, the username, and where
 * sync is up to. The device's secret key is in [DesktopKeyVault]; the password is never kept.
 */
class DesktopSyncPrefs(val dir: File) : SyncPrefs {
    private val file = File(dir, FILE)
    private val props = Properties()

    init {
        try {
            if (file.isFile) file.inputStream().use { props.load(it) }
        } catch (e: Exception) {
            props.clear()
        }
    }

    private fun str(k: String): String? = props.getProperty(k)
    private fun setStr(k: String, v: String?) {
        if (v == null) props.remove(k) else props.setProperty(k, v)
        save()
    }
    private fun long(k: String) = props.getProperty(k)?.toLongOrNull() ?: 0L

    override var serverUrl: String? get() = str("url"); set(v) = setStr("url", v)
    override var serverKey: String? get() = str("server_key"); set(v) = setStr("server_key", v)
    override var deviceId: String? get() = str("device_id"); set(v) = setStr("device_id", v)
    override var deviceName: String? get() = str("device_name"); set(v) = setStr("device_name", v)
    override var username: String? get() = str("username"); set(v) = setStr("username", v)
    override var phase: String? get() = str("phase"); set(v) = setStr("phase", v)
    override var pushedSeq: Long get() = long("pushed_seq"); set(v) = setStr("pushed_seq", v.toString())
    override var pulledSeq: Long get() = long("pulled_seq"); set(v) = setStr("pulled_seq", v.toString())
    override var lastSyncedAt: Long get() = long("last_synced_at"); set(v) = setStr("last_synced_at", v.toString())
    override var conflictsSeenAt: Long get() = long("conflicts_seen_at"); set(v) = setStr("conflicts_seen_at", v.toString())
    override var revoked: Boolean get() = str("revoked") == "true"; set(v) = setStr("revoked", v.toString())
    /** Stay logged in (0.11.0), from the Log in screen; missing means on. */
    override var stayLoggedIn: Boolean get() = str("stay_logged_in") != "false"; set(v) = setStr("stay_logged_in", v.toString())

    override fun pinned(url: String): String? = str("pin.$url")
    override fun pin(url: String, keyHex: String) = setStr("pin.$url", keyHex)

    @Synchronized
    private fun save() {
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        tmp.outputStream().use { props.store(it, "Dead Drop Finance (desktop) server connection: nothing secret") }
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val FILE = "server.properties"
    }
}

/**
 * This computer's server secret key, protected with Windows DPAPI for this Windows user (as the budget's own key is),
 * in %APPDATA%\DeadDropFinance\server-device.key. Never a plain file. Log out deletes it.
 */
class DesktopKeyVault(dir: File, private val protector: KeyProtector = Dpapi) : KeyVault {
    private val file = File(dir, FILE)

    override fun load(): ByteArray? = try {
        if (file.isFile) protector.unprotect(file.readBytes()) else null
    } catch (e: Exception) {
        null
    }

    override fun save(key: ByteArray) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeBytes(protector.protect(key))
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    override fun delete() { file.delete() }

    companion object {
        const val FILE = "server-device.key"
    }
}

/**
 * This computer's recovery keys (0.11.0), protected with Windows DPAPI for this Windows user, in
 * %APPDATA%\DeadDropFinance\recovery.key: the current key, earlier ones kept for older backups, and whether the current
 * one still waits for "I saved it". Never a plain file, never logged.
 */
class DesktopRecoveryStore(dir: File, private val protector: KeyProtector = Dpapi) : net.darkrelay.deaddropfinance.backup.RecoveryKeyStore {
    private val file = File(dir, FILE)

    private fun load(): Properties = Properties().also { p ->
        try {
            if (file.isFile) p.load(java.io.StringReader(String(protector.unprotect(file.readBytes()), Charsets.UTF_8)))
        } catch (e: Exception) {
            p.clear()
        }
    }

    private fun save(p: Properties) {
        file.parentFile.mkdirs()
        val text = java.io.StringWriter().also { p.store(it, null) }.toString()
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeBytes(protector.protect(text.toByteArray(Charsets.UTF_8)))
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun olds(p: Properties): List<String> = p.getProperty("old", "").split(',').filter { it.isNotBlank() }

    @Synchronized override fun current(): ByteArray? = load().getProperty("current")?.takeIf { it.isNotBlank() }?.unhex()

    @Synchronized override fun old(): List<ByteArray> = olds(load()).map { it.unhex() }

    @Synchronized override fun setCurrent(key: ByteArray, unconfirmed: Boolean) {
        val p = load()
        val cur = p.getProperty("current", "")
        val old = olds(p).toMutableList()
        if (cur.isNotEmpty() && cur != key.hex() && cur !in old) old += cur
        p.setProperty("current", key.hex())
        p.setProperty("old", old.joinToString(","))
        p.setProperty("unconfirmed", unconfirmed.toString())
        save(p)
    }

    @Synchronized override fun unconfirmed(): Boolean = load().getProperty("unconfirmed") == "true"

    @Synchronized override fun confirm() { save(load().also { it.setProperty("unconfirmed", "false") }) }

    companion object {
        const val FILE = "recovery.key"
    }
}

/** An Add a phone code on screen: the code, the QR code's content, and when it stops working by this computer's clock. */
data class PairingView(val code: PairingCode, val qr: String, val expiresAt: Long)

/**
 * The program's side of server sync (0.10.0): the shared ServerSync (the phone's own sync code) on the program's
 * budget, and what Settings > Server, the status pill and the read-only banner show. The edit lock follows the window:
 * held while it's open and not minimized, let go when it's minimized or closed.
 */
class ServerModel(
    val sync: ServerSync,
    private val app: AppModel,
    private val scope: CoroutineScope,
    /** Where a login's backup of this computer's own budget goes (Downloads\DeadDropFinance). */
    private val downloads: File = defaultDownloads(),
    val defaultDeviceName: String = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() } ?: "Desktop",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var status by mutableStateOf(sync.status.value)
        private set
    var notices by mutableStateOf<List<String>>(emptyList())
        private set
    var devices by mutableStateOf<List<DeviceInfo>>(emptyList())
        private set
    /** The Server panel's last problem, in plain words. */
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    /** After Connect: the address and the server key's fingerprint, before logging in. */
    var contacted by mutableStateOf<Pair<String, String>?>(null)
    var pairing by mutableStateOf<PairingView?>(null)
        private set
    /** Take over editing's confirmation is open. */
    var confirmTakeOver by mutableStateOf(false)

    /** Reads [status] too, so a screen showing this redraws when the connection changes. */
    val connected: Boolean get() = status.let { sync.connected }

    init {
        // another device restored a backup (0.12.2): this computer's budget is saved before the server's replaces it
        sync.backupBeforeReplaced = { key -> backupTo(key) }
        scope.launch {
            sync.status.collect {
                status = it
                // the server's changes are written as rows; show them
                if (it.state == SyncStatus.State.SYNCED || it.state == SyncStatus.State.NOT_CONNECTED) runCatching { app.refresh() }
            }
        }
        scope.launch { sync.notices.collect { notices = it } }
        scope.launch {
            sync.recoveryKeyArrived.collect { arrived ->
                if (arrived) {
                    app.message = "The account's recovery key reached this computer: backups made here now open on every device of the account." to false
                    sync.dismissKeyArrived()
                }
            }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit): Boolean {
        busy = true
        error = null
        return try {
            block()
            true
        } catch (e: ServerException) {
            error = e.message
            false
        } catch (e: ServerClient.RpcError) {
            error = e.message
            false
        } catch (e: IllegalArgumentException) {
            error = e.message
            false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = "That didn't work: ${e.message ?: e.javaClass.simpleName}."
            false
        } finally {
            busy = false
        }
    }

    // ---- connecting and logging in

    suspend fun connect(address: String) = guarded {
        val (url, key) = sync.contact(address)
        contacted = url to key.fingerprint
    }

    suspend fun hasLocalData(): Boolean = sync.hasLocalData()

    /**
     * Creates the server's account from this computer (0.11.0) with the server's one-time setup code. This computer's
     * budget becomes the server's copy. The account's recovery key is this computer's, or one made now and shown once.
     */
    suspend fun create(setupCode: String, username: String, pass: String, again: String, deviceName: String): Boolean {
        val url = contacted?.first ?: return false
        if (pass.length < ServerSync.MIN_PASSWORD) { error = "Use at least ${ServerSync.MIN_PASSWORD} characters for the password."; return false }
        if (pass != again) { error = "The two passwords don't match."; return false }
        return guarded {
            sync.createAccount(url, setupCode, username, pass, deviceName.ifBlank { defaultDeviceName })
            contacted = null
            app.message = "Account created. This computer's budget is the server's copy now." to false
            loadDevices()
            app.showRecoveryKeyIfNew()
        }
    }

    /**
     * Log in's button: when this computer has a budget of its own but no recovery key, the key is made and shown first
     * (the backup before the login is encrypted with it); then the confirmation that its budget will be replaced.
     */
    suspend fun startLogin(username: String, pass: String, deviceName: String, stay: Boolean) {
        val confirm = Dialog.ConfirmLogin(username, pass, deviceName, stay)
        if (sync.needsRecoveryKeyFirst()) {
            app.recoveryKeys?.ensure()
            app.dialog = Dialog.ShowRecoveryKey(confirm)
        } else if (sync.hasLocalData()) {
            app.dialog = confirm
        } else {
            login(username, pass, deviceName, stay)
        }
    }

    /**
     * Logs in; the server's budget replaces this one's, after a backup of this one (under its recovery key) when it has
     * data of its own. [stay] false: closing the program logs out, and the next open asks for the login again.
     */
    suspend fun login(username: String, pass: String, deviceName: String, stay: Boolean = true): Boolean {
        val url = contacted?.first ?: return false
        return guarded {
            val saved = sync.login(url, username, pass, deviceName.ifBlank { defaultDeviceName }, stay) { k -> backupTo(k) }
            contacted = null
            app.message = (if (saved != null) "Logged in. This computer's old budget was backed up to $saved, encrypted with its recovery key." else "Logged in.") to false
            loadDevices()
        }
    }

    /** Password change (0.11.0): no old password needed, this computer is logged in. Other devices stay logged in. */
    suspend fun changePassword(pass: String, again: String): Boolean {
        if (pass.length < ServerSync.MIN_PASSWORD) { error = "Use at least ${ServerSync.MIN_PASSWORD} characters for the password."; return false }
        if (pass != again) { error = "The two passwords don't match."; return false }
        return guarded {
            sync.changePassword(pass)
            app.message = "Password changed. Your other devices stay logged in; use the new password for the next login." to false
        }
    }

    /** Opening: with Stay logged in off, a close that didn't finish logging out is finished now (a few seconds at most). */
    suspend fun openingCheck() { withTimeoutOrNull(6_000) { sync.openingCheck() } }

    /** The encrypted backup a login makes first (the shared backup format, this computer's recovery key). Never overwrites a file. */
    private suspend fun backupTo(key: ByteArray): String {
        val dir = File(downloads, "DeadDropFinance").apply { mkdirs() }
        val name = net.darkrelay.deaddropfinance.backup.DesktopBackups.fileName(app.today())
        var f = File(dir, name)
        var n = 2
        while (f.exists()) f = File(dir, name.removeSuffix(".ddfbak") + "-$n.ddfbak").also { n++ }
        app.writeBackup(f, key)
        return f.absolutePath
    }

    // ---- the lock follows the window

    private var ticker: Job? = null
    private var leaving: Job? = null

    /** The window is open and not minimized: take the lock if it's free and keep it with a heartbeat every 40 s. */
    fun shown() {
        val before = leaving
        ticker?.cancel()
        ticker = scope.launch {
            before?.join()
            sync.foreground()
            while (isActive) {
                delay(ServerSync.HEARTBEAT_MS)
                sync.tick()
            }
        }
    }

    /** Minimized: send what's waiting and let the lock go. */
    fun hidden() {
        val t = ticker
        ticker = null
        t?.cancel()
        leaving = scope.launch {
            t?.join()
            sync.background()
        }
    }

    /**
     * Closing: the same as minimized, waited for (a few seconds at most, so closing never hangs). With Stay logged in
     * off (0.11.0) it logs out instead: the lock let go, this computer removed on the server, its key deleted here.
     */
    suspend fun closing() {
        ticker?.cancel()
        withTimeoutOrNull(6_000) { if (!sync.closing()) sync.background() }
    }

    fun syncNow() { scope.launch { sync.syncNow() } }

    // ---- take over, devices, logging out

    suspend fun takeOver() {
        confirmTakeOver = false
        sync.takeOver()
    }

    suspend fun loadDevices() = guarded { devices = sync.devices() }

    suspend fun revoke(id: String) = guarded {
        sync.revoke(id)
        if (sync.connected) devices = sync.devices() else devices = emptyList()
    }

    suspend fun logout() = guarded {
        sync.logout()
        devices = emptyList()
        pairing = null
        app.message = "Logged out. This computer's budget stays here." to false
    }

    // ---- Add a phone

    suspend fun newPairingCode() = guarded {
        val (code, qr) = sync.newPairingCode("Phone")
        // the countdown runs on this computer's clock, for as long as the server gave the code
        pairing = PairingView(code, qr, clock() + (code.expiresAt - code.createdAt))
    }

    fun closePairing() { pairing = null }

    companion object {
        fun defaultDownloads(): File = File(System.getProperty("user.home"), "Downloads")

        fun pillText(s: SyncStatus, time: (Long) -> String = ::clockTime): String = when (s.state) {
            SyncStatus.State.NOT_CONNECTED -> "Not connected"
            SyncStatus.State.SYNCING -> "Syncing"
            SyncStatus.State.SYNCED -> if (s.lastSyncedAt > 0) "Synced ${time(s.lastSyncedAt)}" else "Synced"
            SyncStatus.State.OFFLINE -> if (s.queued > 0) "Offline, ${s.queued} ${if (s.queued == 1) "change" else "changes"} waiting" else "Offline"
            SyncStatus.State.ERROR -> "Sync error"
        }

        fun clockTime(at: Long): String = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))

        /** The fingerprint in groups of four, as the phone shows it. */
        fun groups(fp: String): String = fp.chunked(4).joinToString(" ")
    }
}
