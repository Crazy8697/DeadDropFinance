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
        scope.launch {
            sync.status.collect {
                status = it
                // the server's changes are written as rows; show them
                if (it.state == SyncStatus.State.SYNCED || it.state == SyncStatus.State.NOT_CONNECTED) runCatching { app.refresh() }
            }
        }
        scope.launch { sync.notices.collect { notices = it } }
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

    /** Logs in; the server's budget replaces this one's, after a backup of this one when it has data of its own. */
    suspend fun login(username: String, pass: String, deviceName: String): Boolean {
        val url = contacted?.first ?: return false
        return guarded {
            val saved = sync.login(url, username, pass, deviceName.ifBlank { defaultDeviceName }) { p -> backupTo(p) }
            contacted = null
            app.message = (if (saved != null) "Logged in. This computer's old budget was backed up to $saved, encrypted with the account password." else "Logged in.") to false
            loadDevices()
        }
    }

    /** The encrypted backup a login makes first (the shared backup format, the account password). Never overwrites a file. */
    private suspend fun backupTo(pass: CharArray): String {
        val dir = File(downloads, "DeadDropFinance").apply { mkdirs() }
        val name = net.darkrelay.deaddropfinance.backup.DesktopBackups.fileName(app.today())
        var f = File(dir, name)
        var n = 2
        while (f.exists()) f = File(dir, name.removeSuffix(".ddfbak") + "-$n.ddfbak").also { n++ }
        app.writeBackup(f, pass)
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

    /** Closing: the same as minimized, waited for (a few seconds at most, so closing never hangs). */
    suspend fun closing() {
        ticker?.cancel()
        withTimeoutOrNull(5_000) { sync.background() }
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
