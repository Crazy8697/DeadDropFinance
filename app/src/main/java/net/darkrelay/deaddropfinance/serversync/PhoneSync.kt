package net.darkrelay.deaddropfinance.serversync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.darkrelay.deaddropfinance.data.SecretStore

/**
 * The phone's server connection settings, in their own "server" settings file. Nothing here is secret: the address,
 * the pinned public keys, this device's id on the server, its name, the username, and where sync is up to. The
 * device's secret key is in SecretStore (ServerKeyVault); the password is never kept.
 */
class ServerPrefs(private val prefs: SharedPreferences) : SyncPrefs {

    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private fun str(k: String) = prefs.getString(k, null)
    private fun setStr(k: String, v: String?) = prefs.edit { if (v == null) remove(k) else putString(k, v) }

    override var serverUrl: String? get() = str("url"); set(v) = setStr("url", v)
    override var serverKey: String? get() = str("server_key"); set(v) = setStr("server_key", v)
    override var deviceId: String? get() = str("device_id"); set(v) = setStr("device_id", v)
    override var deviceName: String? get() = str("device_name"); set(v) = setStr("device_name", v)
    override var username: String? get() = str("username"); set(v) = setStr("username", v)
    override var phase: String? get() = str("phase"); set(v) = setStr("phase", v)
    override var pushedSeq: Long get() = prefs.getLong("pushed_seq", 0); set(v) = prefs.edit { putLong("pushed_seq", v) }
    override var pulledSeq: Long get() = prefs.getLong("pulled_seq", 0); set(v) = prefs.edit { putLong("pulled_seq", v) }
    override var lastSyncedAt: Long get() = prefs.getLong("last_synced_at", 0); set(v) = prefs.edit { putLong("last_synced_at", v) }
    override var conflictsSeenAt: Long get() = prefs.getLong("conflicts_seen_at", 0); set(v) = prefs.edit { putLong("conflicts_seen_at", v) }
    override var revoked: Boolean get() = prefs.getBoolean("revoked", false); set(v) = prefs.edit { putBoolean("revoked", v) }
    /** Stay logged in (1.6.0), from the Log in screen; missing means on. */
    override var stayLoggedIn: Boolean get() = prefs.getBoolean("stay_logged_in", true); set(v) = prefs.edit { putBoolean("stay_logged_in", v) }

    override fun pinned(url: String): String? = str("pin:$url")
    override fun pin(url: String, keyHex: String) = setStr("pin:$url", keyHex)

    companion object {
        const val FILE = "server"
    }
}

/**
 * This phone's recovery keys (1.6.0), in SecretStore (wrapped by its own Keystore key), never in the database,
 * SharedPreferences or a backup: the current key, earlier ones kept for older backups, and whether the current one still
 * waits for "I saved it".
 */
class PhoneRecoveryStore(private val secrets: () -> net.darkrelay.deaddropfinance.data.SecretStore) : net.darkrelay.deaddropfinance.backup.RecoveryKeyStore {
    override fun current(): ByteArray? = secrets().get(CURRENT)?.unhex()

    override fun old(): List<ByteArray> = secrets().get(OLD)?.split(',')?.filter { it.isNotBlank() }?.map { it.unhex() } ?: emptyList()

    @Synchronized override fun setCurrent(key: ByteArray, unconfirmed: Boolean) {
        val s = secrets()
        val cur = s.get(CURRENT)
        val old = (s.get(OLD)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()).toMutableList()
        if (cur != null && cur != key.hex() && cur !in old) old += cur
        if (old.isNotEmpty()) s.put(OLD, old.joinToString(","))
        s.put(CURRENT, key.hex())
        s.put(UNCONFIRMED, if (unconfirmed) "1" else "0")
    }

    override fun unconfirmed(): Boolean = secrets().get(UNCONFIRMED) == "1"

    override fun confirm() = secrets().put(UNCONFIRMED, "0")

    companion object {
        const val CURRENT = "recovery_key"
        const val OLD = "recovery_key_old"
        const val UNCONFIRMED = "recovery_key_unconfirmed"
    }
}

/** This device's server secret key, in SecretStore (wrapped by its own Keystore key). Log out deletes it. */
class ServerKeyVault(private val secrets: () -> SecretStore) : KeyVault {
    override fun load(): ByteArray? = runCatching { secrets().get(NAME)?.unhex() }.getOrNull()
    override fun save(key: ByteArray) = secrets().put(NAME, key.hex())
    override fun delete() { runCatching { secrets().remove(NAME) } }

    companion object {
        const val NAME = "server_device_key"
    }
}

/**
 * Ties the edit lock to the app being in front: when any of the app is on screen, sync takes the lock if it's free
 * and sends a heartbeat every 40 s (the server lets it lapse after 120 s without one); when the app goes to the back
 * or closes, it sends what's waiting and lets the lock go.
 */
class SyncLifecycle(private val sync: ServerSync, private val scope: CoroutineScope) : DefaultLifecycleObserver {
    private var ticker: Job? = null

    fun attach() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

    fun detach() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        ticker?.cancel()
    }

    override fun onStart(owner: LifecycleOwner) {
        ticker?.cancel()
        ticker = scope.launch {
            sync.foreground()
            while (isActive) {
                delay(ServerSync.HEARTBEAT_MS)
                sync.tick()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        ticker?.cancel()
        ticker = null
        scope.launch { sync.background() }
    }
}
