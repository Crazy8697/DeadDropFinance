package net.darkrelay.deaddropfinance.serversync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.darkrelay.deaddropfinance.backup.RecoveryKey
import net.darkrelay.deaddropfinance.backup.RecoveryKeys
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ReadOnlyException
import net.darkrelay.deaddropfinance.data.Synced
import net.darkrelay.deaddropfinance.data.newId
import org.json.JSONObject

/** What the phone keeps about its server connection (SharedPreferences on the phone). Nothing here is secret. */
interface SyncPrefs {
    var serverUrl: String?
    /** The pinned server key (hex) of [serverUrl]. */
    var serverKey: String?
    /** This device's id on the server, from setup or login. */
    var deviceId: String?
    var deviceName: String?
    var username: String?
    /** What the next sync does first: UPLOAD (the budget goes up as the master copy), TAKEOVER (the server's replaces this one's), or READY. */
    var phase: String?
    /** The last change_log seq sent to the server, and the last server seq taken in. */
    var pushedSeq: Long
    var pulledSeq: Long
    var lastSyncedAt: Long
    /** The newest last-write-wins note already shown. */
    var conflictsSeenAt: Long
    /** The server said this device was removed. */
    var revoked: Boolean
    /**
     * Stay logged in (1.6.0), from the Log in screen: true keeps this device logged in until Log out. False logs it out
     * when the app or program closes, and the next open asks for the login again.
     */
    var stayLoggedIn: Boolean
    /** The key pinned on first contact with an address; kept after logging out, so a changed key is still noticed. */
    fun pinned(url: String): String?
    fun pin(url: String, keyHex: String)
}

/** Where the device's secret key lives (the phone's SecretStore, Keystore-wrapped). */
interface KeyVault {
    fun load(): ByteArray?
    fun save(key: ByteArray)
    fun delete()
}

data class SyncStatus(
    val state: State,
    /** Changes made here that haven't reached the server yet (the offline queue, kept in the change log). */
    val queued: Int = 0,
    val lastSyncedAt: Long = 0,
    val message: String? = null,
    /** Another device holds the edit lock, so this one is read only: that device's name. */
    val readOnlyBy: String? = null,
    /** The handoff notice (1.5.0): this device took over the lock from this one, whose waiting changes stay here until it edits again. */
    val takenOverBy: String? = null,
) {
    enum class State { NOT_CONNECTED, SYNCING, SYNCED, OFFLINE, ERROR }
}

/**
 * Server sync (1.4.0): this device and the user's own Dead Drop server. Local changes are the change log's rows (so
 * the offline queue survives the app closing); the server's changes come in through [pull] and are written as rows,
 * last write winning by the change's time, as the server decides. One device edits at a time: the one holding the
 * server's edit lock. Every call is a libsodium envelope to the pinned server key (Box, ServerClient).
 */
class ServerSync(
    private val store: BudgetStore,
    private val prefs: SyncPrefs,
    private val vault: KeyVault,
    private val scope: CoroutineScope,
    private val transportFor: (String) -> Transport = { HttpTransport(it) },
    private val kind: String = "phone",
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long a run of writes settles before it's sent. */
    private val sendDelayMs: Long = 1_500,
    /** This device's recovery keys (1.6.0); null leaves the account's key out (tests of sync alone). */
    private val recovery: RecoveryKeys? = null,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus(if (connected) SyncStatus.State.SYNCING else SyncStatus.State.NOT_CONNECTED, lastSyncedAt = prefs.lastSyncedAt))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _notices = MutableStateFlow<List<String>>(emptyList())
    /** Last-write-wins notes: a change made here was replaced by a newer one from another device. */
    val notices: StateFlow<List<String>> = _notices.asStateFlow()
    private val _keyArrived = MutableStateFlow(false)
    /** The account's recovery key reached this device through the server (1.6.0): its backups now use it. */
    val recoveryKeyArrived: StateFlow<Boolean> = _keyArrived.asStateFlow()

    @Volatile private var foreground = false
    @Volatile private var holdsLock = false
    @Volatile private var readOnlyBy: String? = null
    @Volatile private var takenBy: String? = null
    @Volatile private var keyChecked = false
    @Volatile private var names: Map<String, String> = emptyMap()
    private var sendJob: Job? = null

    init {
        store.guard = { readOnlyBy?.let { throw ReadOnlyException(it) } }
        store.onWrite = { changed() }
    }

    val connected: Boolean get() = prefs.deviceId != null && prefs.serverKey != null && vault.load() != null

    /** This device held the edit lock at its last word with the server. */
    val holdsEditLock: Boolean get() = holdsLock

    // what the Server screen shows (nothing secret)
    val serverUrl: String? get() = prefs.serverUrl
    val username: String? get() = prefs.username
    val deviceName: String? get() = prefs.deviceName
    val deviceId: String? get() = prefs.deviceId
    val fingerprint: String? get() = prefs.serverKey?.let { runCatching { Box.fingerprint(it.unhex()) }.getOrNull() }
    val stayLoggedIn: Boolean get() = prefs.stayLoggedIn

    fun dismissNotice(n: String) { _notices.value = _notices.value - n }

    // ---- connecting

    /**
     * First contact with [address]: its key, refused if this address had a different one pinned, or if it doesn't match
     * [fingerprint] (from a QR code). Pins it otherwise.
     */
    suspend fun contact(address: String, fingerprint: String? = null): Pair<String, ServerKeyInfo> = withContext(Dispatchers.IO) {
        val url = HttpTransport.normalize(address)
        val key = ServerClient.serverKey(transportFor(url))
        if (fingerprint != null && !PairingQr.sameFingerprint(fingerprint, key.fingerprint)) throw ServerException(ServerException.Kind.KEY_CHANGED, ServerClient.QR_MISMATCH)
        val pinned = prefs.pinned(url)
        if (pinned != null && pinned != key.publicKey.hex()) throw ServerException(ServerException.Kind.KEY_CHANGED, ServerClient.KEY_CHANGED)
        prefs.pin(url, key.publicKey.hex())
        url to key
    }

    /**
     * Creates the server's account with its one-time setup code. This device's budget then goes up as the master copy.
     * The account's recovery key (1.6.0) is this device's own if it has one, or one made now, which the screen then
     * shows once (RecoveryKeys.toShow).
     */
    suspend fun createAccount(address: String, setupCode: String, username: String, pass: String, deviceName: String) {
        mutex.withLock {
            check(!connected) { "already connected" }
            val (url, key) = contact(address)
            val keys = Box.keyPair()
            val keyId = recovery?.ensure()?.let(RecoveryKey::id)
            val r = withContext(Dispatchers.IO) {
                ServerClient(transportFor(url), key.publicKey, keys, clock = clock).setup(setupCode.trim(), username.trim(), pass, deviceName.trim(), kind, keyId)
            }
            remember(url, key, keys, r, username.trim(), PHASE_UPLOAD, stay = true)
        }
        syncNow()
    }

    /**
     * Before a login or QR pairing replaces this device's budget (1.6.0): true when it has a budget of its own but no
     * recovery key yet. The screen then makes one (RecoveryKeys.ensure) and shows it before going on, since the backup
     * made first is encrypted with it.
     */
    suspend fun needsRecoveryKeyFirst(): Boolean = recovery != null && recovery.current() == null && hasLocalData()

    /**
     * Logs this device in. When the server holds a budget it replaces this device's; when this device has data of its
     * own, [backup] saves it first, encrypted with this device's recovery key (1.6.0; made and shown first, see
     * [needsRecoveryKeyFirst]), and returns where. Returns that, or null. [stayLoggedIn] false: closing logs out.
     */
    suspend fun login(
        address: String, username: String, pass: String, deviceName: String, stayLoggedIn: Boolean = true,
        backup: suspend (ByteArray) -> String,
    ): String? {
        var savedTo: String? = null
        mutex.withLock {
            check(!connected) { "already connected" }
            val (url, key) = contact(address)
            if (hasLocalData()) savedTo = backup(backupKey())
            val keys = Box.keyPair()
            val r = withContext(Dispatchers.IO) {
                ServerClient(transportFor(url), key.publicKey, keys, clock = clock).login(username.trim(), pass, deviceName.trim(), kind)
            }
            remember(url, key, keys, r, username.trim(), PHASE_TAKEOVER, stayLoggedIn)
        }
        syncNow()
        return savedTo
    }

    /** The key the backup before a login or pairing uses: this device's recovery key (it must have one by now). */
    private fun backupKey(): ByteArray = recovery?.current()
        ?: throw IllegalStateException("This device needs its recovery key first, so the backup of its budget can be opened later.")

    /**
     * Pairs this device from a QR code (1.5.0): the address, the key fingerprint it must match, and a one-time code
     * that's redeemed for this device's own key. Then as a login: when this device has data of its own, [backup] saves it
     * first, encrypted with this device's recovery key (1.6.0), and the server's copy replaces it. Returns where the
     * backup went, or null.
     */
    suspend fun pair(qr: PairingQr.Info, deviceName: String, backup: suspend (ByteArray) -> String): String? {
        var savedTo: String? = null
        mutex.withLock {
            check(!connected) { "already connected" }
            val (url, key) = contact(qr.serverUrl, qr.fingerprint)
            if (hasLocalData()) savedTo = backup(backupKey())
            val keys = Box.keyPair()
            val r = withContext(Dispatchers.IO) { ServerClient(transportFor(url), key.publicKey, keys, clock = clock).pair(qr.code, deviceName.trim(), kind) }
            remember(url, key, keys, r, null, PHASE_TAKEOVER, stay = true)
        }
        syncNow()
        return savedTo
    }

    /** A one-time pairing code for a phone (Add a phone), and the QR code's content for it. */
    suspend fun newPairingCode(name: String = "Phone"): Pair<PairingCode, String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val code = client().pairingCode(name)
            code to PairingQr.encode(prefs.serverUrl!!, fingerprint!!, code.code)
        }
    }

    private fun remember(url: String, key: ServerKeyInfo, keys: Box.KeyPair, r: PairReply, username: String?, phase: String, stay: Boolean) {
        vault.save(keys.secretKey)
        prefs.serverUrl = url
        prefs.serverKey = key.publicKey.hex()
        prefs.deviceId = r.deviceId
        prefs.deviceName = r.name
        prefs.username = username
        prefs.phase = phase
        prefs.pushedSeq = 0
        prefs.pulledSeq = 0
        prefs.conflictsSeenAt = r.serverTime
        prefs.revoked = false
        prefs.stayLoggedIn = stay
        keyChecked = true
        // logged in with the account's key already here (this device made it, or logged in before): nothing to wait for
        _keyArrived.value = false
    }

    /** This device has a budget of its own: an account, a transaction, a move, a bill, or an envelope of its own. */
    suspend fun hasLocalData(): Boolean {
        val d = store.dao
        return d.accountsNow().isNotEmpty() || d.transactionsNow().isNotEmpty() || d.movesNow().isNotEmpty() || d.billsNow().isNotEmpty() ||
            d.envelopesNow().any { it.mode != net.darkrelay.deaddropfinance.data.EnvelopeMode.ROLLOVER && !it.unplanned }
    }

    // ---- the lock follows the app: held while it's in front, let go when it goes to the back

    /** The app came to the front: take the edit lock if it's free, then sync. Call [tick] every [HEARTBEAT_MS] while in front. */
    suspend fun foreground() { foreground = true; syncNow() }

    suspend fun tick() = syncNow()

    /**
     * Take over editing (1.5.0): the lock moves here from the device holding it, which turns read only and keeps its
     * waiting changes until it holds the lock again.
     */
    suspend fun takeOver() {
        mutex.withLock {
            if (!connected || prefs.revoked) return
            publish(SyncStatus.State.SYNCING)
            val ok = withContext(Dispatchers.IO) {
                try {
                    takeLock(client().takeover())
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    fail(e)
                    false
                }
            }
            if (!ok) return
        }
        syncNow()
    }

    /** The app went to the back or closed: send what's waiting, then let go of the lock. */
    suspend fun background() {
        foreground = false
        readOnlyBy = null
        mutex.withLock {
            if (!connected || prefs.revoked) return
            withContext(Dispatchers.IO) {
                try {
                    val c = client()
                    if (prefs.phase == PHASE_READY) pushAll(c)
                    c.heartbeat(open = false)
                    holdsLock = false
                    prefs.lastSyncedAt = clock()
                    publish(SyncStatus.State.SYNCED)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    holdsLock = false
                    fail(e)
                }
            }
        }
    }

    /** A write here logged a change: send it shortly (writes close together go as one). */
    fun changed() {
        sendJob?.cancel()
        sendJob = scope.launch {
            if (!connected || _status.value.state == SyncStatus.State.OFFLINE) { refresh(); return@launch }
            delay(sendDelayMs)
            syncNow()
        }
    }

    // ---- syncing

    /** Sync Now: the first upload or takeover if one is due, then the server's changes in, then this device's out. */
    suspend fun syncNow() {
        mutex.withLock {
            if (!connected) { readOnlyBy = null; publish(SyncStatus.State.NOT_CONNECTED); return }
            if (prefs.revoked) { readOnlyBy = null; publish(SyncStatus.State.ERROR, ServerClient.REVOKED); return }
            publish(SyncStatus.State.SYNCING)
            withContext(Dispatchers.IO) {
                try {
                    val c = client()
                    if (!keyChecked) {
                        val key = ServerClient.serverKey(transportFor(prefs.serverUrl!!))
                        if (key.publicKey.hex() != prefs.serverKey) throw ServerException(ServerException.Kind.KEY_CHANGED, ServerClient.KEY_CHANGED)
                        keyChecked = true
                    }
                    val st = c.status()
                    names = st.devices.associate { it.id to it.name }
                    var lock = st.lock
                    if (foreground) lock = if (lock.youHoldIt) c.heartbeat(open = true) else if (lock.holder == null) c.acquire() else lock
                    else if (lock.youHoldIt) lock = c.heartbeat(open = false)
                    takeLock(lock)
                    when (prefs.phase) {
                        PHASE_UPLOAD -> upload(c)
                        PHASE_TAKEOVER -> takeover(c, st.lastSeq)
                    }
                    pullAll(c)
                    pushAll(c)
                    // the recovery key handoff never stops a sync: it tries again next time
                    runCatching { handOffRecoveryKey(c) }.onFailure { if (it is CancellationException) throw it }
                    prefs.lastSyncedAt = clock()
                    publish(SyncStatus.State.SYNCED)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    fail(e)
                }
            }
        }
    }

    private suspend fun fail(e: Exception) {
        when {
            e is ServerException && e.kind == ServerException.Kind.UNREACHABLE -> { readOnlyBy = null; publish(SyncStatus.State.OFFLINE) }
            e is ServerException && e.kind == ServerException.Kind.REVOKED -> {
                prefs.revoked = true; holdsLock = false; readOnlyBy = null
                publish(SyncStatus.State.ERROR, ServerClient.REVOKED)
            }
            e is ServerException && e.kind == ServerException.Kind.KEY_CHANGED -> {
                keyChecked = false; readOnlyBy = null
                publish(SyncStatus.State.ERROR, ServerClient.KEY_CHANGED)
            }
            e is ServerException || e is ServerClient.RpcError -> publish(SyncStatus.State.ERROR, e.message)
            else -> publish(SyncStatus.State.ERROR, "Sync stopped: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun takeLock(l: LockState) {
        holdsLock = l.youHoldIt
        takenBy = if (l.takenBy != null && !l.youHoldIt) l.takenByName ?: "Another device" else null
        readOnlyBy = if (foreground && !l.youHoldIt && l.holder != null) l.holderName ?: "Another device" else null
    }

    private fun client(): ServerClient {
        val secret = vault.load() ?: throw ServerException(ServerException.Kind.REVOKED, "This phone's server key is gone. Log in again.")
        return ServerClient(transportFor(prefs.serverUrl!!), prefs.serverKey!!.unhex(), Box.KeyPair(Box.publicKeyOf(secret), secret), prefs.deviceId, clock)
    }

    /** The creating device: its whole budget goes up as the master copy, one INSERT per row. */
    private suspend fun upload(c: ServerClient) {
        if (!holdsLock) {
            val l = c.acquire()
            takeLock(l)
            if (!l.youHoldIt) throw ServerException(ServerException.Kind.ERROR, "Another device holds the edit lock. The budget goes up once it closes.")
        }
        val upTo = store.dao.lastSeq()
        var last = 0L
        for (chunk in snapshot().chunked(PAGE)) last = c.push(chunk, baseSeq = 0, offline = false).lastSeq
        prefs.pushedSeq = upTo
        prefs.pulledSeq = last
        prefs.phase = PHASE_READY
    }

    /** Every live row and the budget start date, as INSERTs with all their fields. */
    internal suspend fun snapshot(): List<Change> {
        val d = store.dao
        val rows: List<Synced> = d.accountsNow() + d.envelopesNow() + d.transactionsNow() + d.linesNow() + d.movesNow() + d.billsNow() +
            d.billPaymentsNow() + d.rulesNow() + d.receiptsNow() + d.receiptItemsNow() + d.itemReturnsNow() + d.subscriptionsNow() +
            d.parkedBuysNow() + d.categoriesNow()
        val t = clock()
        val out = rows.map { e ->
            val f = JSONObject()
            for ((k, v) in e.fields()) f.put(k, v ?: JSONObject.NULL)
            f.put("created_at", e.createdAt).put("updated_at", e.updatedAt)
            Change(newId(), BudgetStore.entityType(e), e.id, "INSERT", f, t)
        }
        val start = store.budgetStart() ?: return out
        return out + Change(newId(), BudgetStore.META_ENTITY, BudgetStore.META_BUDGET_START, "UPDATE", JSONObject().put("value", start.toString()), t)
    }

    /** A device that logged in: the server's copy replaces this one's (the backup was made before logging in). */
    private suspend fun takeover(c: ServerClient, serverLastSeq: Long) {
        if (serverLastSeq == 0L) { prefs.phase = PHASE_UPLOAD; upload(c); return }
        val records = mutableListOf<Record>()
        var afterType = ""; var afterId = ""
        while (true) {
            val page = c.records(afterType, afterId, PAGE)
            records += page.records
            if (!page.more || page.records.isEmpty()) break
            afterType = page.records.last().entityType; afterId = page.records.last().entityId
        }
        store.db.rawTransaction { raw ->
            for (t in TABLES.values) raw.exec("DELETE FROM `$t`")
            raw.exec("DELETE FROM change_log")
            raw.exec("DELETE FROM meta WHERE `key` = ?", listOf(BudgetStore.META_BUDGET_START))
            val cols = mutableMapOf<String, Set<String>>()
            for (r in records) {
                if (r.entityType == BudgetStore.META_ENTITY) {
                    if (r.entityId == BudgetStore.META_BUDGET_START && !r.deleted) writeMeta(raw, r.data)
                    continue
                }
                val table = TABLES[r.entityType] ?: continue
                val data = JSONObject(r.data.toString()).put("deleted", r.deleted)
                upsertRow(raw, table, cols.getOrPut(table) { columns(raw, table) }, r.entityId, data, r.changedAt)
            }
        }
        prefs.pushedSeq = store.dao.lastSeq()
        prefs.pulledSeq = serverLastSeq
        prefs.phase = PHASE_READY
    }

    private suspend fun pullAll(c: ServerClient) {
        val me = prefs.deviceId
        while (true) {
            val r = c.pull(prefs.pulledSeq, PAGE)
            val theirs = r.changes.filter { it.deviceId != me }
            if (theirs.isNotEmpty()) applyRemote(theirs)
            prefs.pulledSeq = r.lastSeq
            noteConflicts(r.conflicts)
            if (!r.more || r.changes.isEmpty()) break
        }
    }

    /** The server's changes as rows, last write winning: a change older than this device's newest for that row is kept out. */
    internal suspend fun applyRemote(changes: List<ServerChange>) {
        store.db.rawTransaction { raw ->
            val cols = mutableMapOf<String, Set<String>>()
            var seq = (raw.query("SELECT COALESCE(MAX(seq), 0) FROM change_log").single()[0] as Long)
            for (ch in changes) {
                if (raw.query("SELECT 1 FROM change_log WHERE change_id = ?", listOf(ch.changeId)).isNotEmpty()) continue
                val table = TABLES[ch.entityType]
                if (table == null && !(ch.entityType == BudgetStore.META_ENTITY && ch.entityId == BudgetStore.META_BUDGET_START)) continue
                val newest = raw.query("SELECT MAX(timestamp) FROM change_log WHERE entity_type = ? AND entity_id = ?", listOf(ch.entityType, ch.entityId))
                    .single()[0] as Long?
                if (newest == null || newest <= ch.timestamp) {
                    try {
                        if (table == null) writeMeta(raw, ch.changedFields)
                        else upsertRow(raw, table, cols.getOrPut(table) { columns(raw, table) }, ch.entityId, deletedAs(ch), ch.timestamp)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        continue   // a change this version can't place (a row it never had); the rest go in
                    }
                }
                raw.exec(
                    "INSERT INTO change_log (change_id, entity_type, entity_id, operation, changed_fields, timestamp, device_id, seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(ch.changeId, ch.entityType, ch.entityId, ch.operation, ch.changedFields.toString(), ch.timestamp, REMOTE + ch.deviceId, ++seq),
                )
            }
        }
    }

    private fun deletedAs(ch: ServerChange): JSONObject =
        if (ch.operation == "DELETE") JSONObject(ch.changedFields.toString()).put("deleted", true) else ch.changedFields

    private suspend fun pushAll(c: ServerClient) {
        // taken over: what's waiting stays here until this device holds the lock again (the server refuses it until then)
        if (takenBy != null) return
        val me = store.deviceId()
        while (true) {
            val rows = store.db.rawTransaction { raw ->
                raw.query("SELECT change_id, entity_type, entity_id, operation, changed_fields, timestamp, seq FROM change_log WHERE device_id = ? AND seq > ? ORDER BY seq LIMIT $PAGE",
                    listOf(me, prefs.pushedSeq))
            }
            if (rows.isEmpty()) return
            val changes = rows.map { Change(it[0] as String, it[1] as String, it[2] as String, it[3] as String, JSONObject(it[4] as String), it[5] as Long) }
            val r = try {
                try {
                    c.push(changes, prefs.pulledSeq, offline = !holdsLock)
                } catch (e: ServerClient.RpcError) {
                    // the lock lapsed or another device has it: these were made while this one could edit, so they go as offline changes
                    if (e.code != "locked") throw e
                    holdsLock = false
                    c.push(changes, prefs.pulledSeq, offline = true)
                }
            } catch (e: ServerClient.RpcError) {
                if (e.code != "taken_over") throw e
                holdsLock = false
                takenBy = takenBy ?: "Another device"
                return
            }
            noteConflicts(r.conflicts)
            prefs.pushedSeq = rows.last()[6] as Long
        }
    }

    private fun noteConflicts(cs: List<Conflict>) {
        val fresh = cs.filter { it.at > prefs.conflictsSeenAt && it.loserDevice == prefs.deviceId }.sortedBy { it.at }
        if (fresh.isEmpty()) return
        prefs.conflictsSeenAt = fresh.last().at
        _notices.value = (_notices.value + fresh.map {
            "Your change to ${what(it.entityType)} was replaced by a newer one from ${names[it.winnerDevice] ?: "another device"} (the last change wins)."
        }).distinct()
    }

    // ---- the recovery key handoff (1.6.0)

    /**
     * The account's recovery key travels between its devices sealed: each device that holds it seals a copy to every
     * other device's public key (crypto_box_seal), and the server keeps only those copies, which it can't open. A device
     * that logs in takes the account's key from its copy (its own earlier key is kept for its older backups); a device
     * holding it seals copies for the devices still waiting. An account made before 1.6.0 takes the first key offered.
     */
    private fun handOffRecoveryKey(c: ServerClient) {
        val rk = recovery ?: return
        var st = c.recoveryStatus()
        val mine = rk.current()
        if (st.keyId == null) {
            if (mine == null) return
            st = c.recoverySet(RecoveryKey.id(mine))
        }
        val accountId = st.keyId ?: return
        if (mine == null || RecoveryKey.id(mine) != accountId) {
            if (!st.mine) return
            val (_, sealed) = c.recoveryGet()
            val secret = vault.load() ?: return
            val key = sealed?.let { Box.sealOpen(it, Box.publicKeyOf(secret), secret) } ?: return
            if (key.size != RecoveryKey.BYTES || RecoveryKey.id(key) != accountId) return
            rk.adopt(key)
            _keyArrived.value = true
            return
        }
        for (d in st.needing) c.recoveryPut(d.deviceId, accountId, Box.seal(mine, d.publicKey))
    }

    /** The screen showed that the account's key arrived. */
    fun dismissKeyArrived() { _keyArrived.value = false }

    // ---- password change (1.6.0)

    /** Sets a new account password from this logged-in device (no old one needed). Other devices stay logged in. */
    suspend fun changePassword(pass: String) {
        require(pass.length >= MIN_PASSWORD) { "Use at least $MIN_PASSWORD characters." }
        mutex.withLock { withContext(Dispatchers.IO) { client().changePassword(pass) } }
    }

    // ---- Stay logged in (1.6.0)

    /**
     * The app or program is closing: with Stay logged in off, this device logs out (lock let go, device removed on the
     * server, its key deleted here). Returns true when it logged out.
     */
    suspend fun closing(): Boolean {
        if (!connected || prefs.stayLoggedIn) return false
        logout()
        return true
    }

    /**
     * On opening: with Stay logged in off and still logged in, the last close didn't finish logging out (the app was
     * stopped first), so it's done now, before anything is shown, and the login is asked for again.
     */
    suspend fun openingCheck(): Boolean = closing()

    // ---- devices and logging out

    suspend fun devices(): List<DeviceInfo> = mutex.withLock { withContext(Dispatchers.IO) { client().devices() } }

    /** Removes another device: its key is deleted on the server, so it can't sync again until it logs in. */
    suspend fun revoke(id: String) {
        if (id == prefs.deviceId) return logout()
        mutex.withLock { withContext(Dispatchers.IO) { client().revoke(id) } }
    }

    /**
     * Logs out: what's waiting is sent if the server can be reached, the lock is let go and this device is removed from
     * the server, then its key is deleted here. The budget stays on this device. Server pins are kept.
     */
    suspend fun logout() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (connected && !prefs.revoked) runCatching {
                    val c = client()
                    if (prefs.phase == PHASE_READY) runCatching { pushAll(c) }
                    // one call (1.6.0): the lock let go and this device removed; an older server gets the two calls
                    try {
                        c.logout()
                    } catch (e: ServerClient.RpcError) {
                        if (e.code != "unknown_op") throw e
                        runCatching { c.heartbeat(open = false) }
                        c.revoke(prefs.deviceId!!)
                    }
                }
            }
            vault.delete()
            prefs.serverUrl = null; prefs.serverKey = null; prefs.deviceId = null; prefs.deviceName = null; prefs.username = null
            prefs.phase = null; prefs.pushedSeq = 0; prefs.pulledSeq = 0; prefs.lastSyncedAt = 0; prefs.revoked = false; prefs.stayLoggedIn = true
            _keyArrived.value = false
            holdsLock = false; readOnlyBy = null; takenBy = null; keyChecked = false
            _notices.value = emptyList()
            publish(SyncStatus.State.NOT_CONNECTED)
        }
    }

    // ---- status

    private suspend fun queued(): Int {
        if (!connected) return 0
        val me = store.deviceId()
        return store.db.rawTransaction { raw ->
            (raw.query("SELECT COUNT(*) FROM change_log WHERE device_id = ? AND seq > ?", listOf(me, prefs.pushedSeq)).single()[0] as Long).toInt()
        }
    }

    private suspend fun publish(state: SyncStatus.State, message: String? = null) {
        _status.value = SyncStatus(state, queued(), prefs.lastSyncedAt, message, readOnlyBy, takenBy)
    }

    /** Recounts the queue (after a write while offline). */
    suspend fun refresh() {
        val s = _status.value
        _status.value = s.copy(queued = queued(), readOnlyBy = readOnlyBy, takenOverBy = takenBy)
    }

    companion object {
        const val PHASE_UPLOAD = "UPLOAD"
        const val PHASE_TAKEOVER = "TAKEOVER"
        const val PHASE_READY = "READY"
        /** The server's lock lapses 120 s after its holder's last heartbeat; a heartbeat every 40 s keeps it. */
        const val HEARTBEAT_MS = 40_000L
        const val PAGE = 500
        /** The account password's shortest length (the server's MIN_PASSWORD). */
        const val MIN_PASSWORD = 8
        /** A change log row from the server has this before the server's device id, so it is never sent back. */
        const val REMOTE = "server:"

        /** The change log's entity types and their tables. */
        val TABLES = mapOf(
            "account" to "account", "envelope" to "envelope", "transaction" to "txn", "transaction_line" to "txn_line",
            "envelope_move" to "envelope_move", "bill" to "bill", "bill_payment" to "bill_payment", "rule" to "rule", "receipt" to "receipt",
            "receipt_item" to "receipt_item", "item_return" to "item_return", "subscription" to "subscription", "parked_buy" to "parked_buy",
            "category" to "category",
        )

        private fun what(type: String) = when (type) {
            "account" -> "an account"
            "envelope" -> "an envelope"
            "transaction" -> "a transaction"
            "transaction_line" -> "a transaction's line"
            "envelope_move" -> "a money move"
            "bill" -> "a bill"
            "bill_payment" -> "a bill payment"
            "rule" -> "a rule"
            "receipt" -> "a receipt"
            "receipt_item" -> "a receipt item"
            "item_return" -> "a return"
            "subscription" -> "a subscription"
            "parked_buy" -> "a parked buy"
            "category" -> "a category"
            BudgetStore.META_ENTITY -> "the budget start date"
            else -> "a record"
        }

        internal suspend fun columns(raw: RawSql, table: String): Set<String> =
            raw.query("SELECT name FROM pragma_table_info(?)", listOf(table)).map { it[0] as String }.toSet()

        internal suspend fun writeMeta(raw: RawSql, data: JSONObject) {
            val v = data.optStringOrNull("value") ?: return
            raw.exec("INSERT OR REPLACE INTO meta (`key`, value) VALUES (?, ?)", listOf(BudgetStore.META_BUDGET_START, v))
        }

        /** Writes [fields] into row [id] of [table]: an update of those columns, or an insert when the row is new. */
        internal suspend fun upsertRow(raw: RawSql, table: String, cols: Set<String>, id: String, fields: JSONObject, at: Long) {
            val values = linkedMapOf<String, Any?>()
            for (k in fields.keys()) if (k in cols && k != "id") values[k] = value(fields.get(k))
            if (raw.query("SELECT 1 FROM `$table` WHERE id = ?", listOf(id)).isNotEmpty()) {
                if ("updated_at" in cols && "updated_at" !in values) values["updated_at"] = at
                if (values.isEmpty()) return
                raw.exec("UPDATE `$table` SET ${values.keys.joinToString(", ") { "`$it` = ?" }} WHERE id = ?", values.values.toList() + id)
            } else {
                if ("created_at" in cols && "created_at" !in values) values["created_at"] = at
                if ("updated_at" in cols && "updated_at" !in values) values["updated_at"] = at
                values["id"] = id
                raw.exec("INSERT INTO `$table` (${values.keys.joinToString(", ") { "`$it`" }}) VALUES (${values.keys.joinToString(", ") { "?" }})", values.values.toList())
            }
        }

        private fun value(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is Boolean -> if (v) 1L else 0L
            is Number -> v.toLong()
            is String -> v
            else -> v.toString()
        }
    }
}
