package net.darkrelay.deaddropfinance.serversync

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** How the client reaches the server: HttpTransport for real, an in-memory server in the tests. */
interface Transport {
    class Response(val status: Int, val body: ByteArray)

    /** Throws IOException when the server can't be reached. */
    fun get(path: String): Response
    fun post(path: String, body: ByteArray): Response
}

/**
 * The device itself blocked the connection, not the network: on Android 17, a server on the local network needs the
 * app's local network permission (Nearby devices). The phone's transport raises it when that permission is missing.
 */
class LocalNetworkBlocked(cause: Throwable? = null) : IOException("local network access is off for this app", cause)

/** Plain http:// to an address outside the home network, tailnet and loopback ranges (1.6.0): refused before sending. */
class PlainHttpRefused : IOException("plain http is only for home network, tailnet and loopback addresses")

/**
 * Why a server call failed, in words the Server screen can show. [reach] (1.6.0): for an UNREACHABLE (or a plain
 * HTTP refusal), what failed on the way. Never carries an address, code, key or password.
 */
class ServerException(val kind: Kind, message: String, val reach: Reach? = null) : Exception(message) {
    /** What failed on the way to the server (1.6.0). */
    enum class Reach {
        /** This device's security policy refused plain http:// to that address. */
        CLEARTEXT_BLOCKED,
        /** Plain http:// to an address outside the home network, tailnet and loopback ranges, refused by the app itself (1.6.0). */
        PLAIN_HTTP_REFUSED,
        /** Android blocked the local network for this app (its local network permission is off). */
        LOCAL_NETWORK_BLOCKED,
        /** The server's name couldn't be looked up (DNS). */
        DNS,
        /** The address answered, but nothing listens on that port: the connection was refused. */
        REFUSED,
        /** No answer in time. */
        TIMEOUT,
        /** No route from this device's network to that address. */
        NO_ROUTE,
        /** The https:// connection failed (certificate or TLS). */
        TLS,
        /** Some other network failure. */
        OTHER,
    }

    enum class Kind {
        /** No answer: no network, or the server is down. Changes wait in the queue. */
        UNREACHABLE,
        /** Setup or login refused: a wrong code, username or password, or too many tries lately. */
        REFUSED,
        /** The server doesn't know this device any more: it was removed (revoked). */
        REVOKED,
        /** The server's key isn't the one this device pinned (or the QR code's fingerprint): never trusted. */
        KEY_CHANGED,
        /** The server answered with an error. */
        ERROR,
    }
}

// What travels inside the envelopes (the server's Protocol.kt), as org.json, so the shared code needs no new library.

class ServerKeyInfo(val publicKey: ByteArray) {
    /** Worked out here from the key, never taken from the server's word. */
    val fingerprint: String get() = Box.fingerprint(publicKey)
}

/** [recoveryKeyId] (1.6.0): the account's recovery key id, so a device that logs in knows whether its own key is it. */
data class PairReply(val deviceId: String, val name: String, val kind: String, val serverTime: Long, val recoveryKeyId: String? = null)

/**
 * The recovery key handoff (1.6.0): the account's key id, whether a sealed copy waits for this device, and the account's
 * other devices that still need one (their public keys, to seal it to).
 */
data class RecoveryStatus(val keyId: String?, val mine: Boolean, val needing: List<NeedsKey>)

data class NeedsKey(val deviceId: String, val name: String, val publicKey: ByteArray)

/**
 * [takenBy] (1.5.0): the handoff notice. Another device took the lock over from this one, which hasn't held it since,
 * so this one's waiting changes stay here until it holds the lock again.
 */
data class LockState(
    val holder: String?, val holderName: String?, val expiresAt: Long?, val youHoldIt: Boolean,
    val takenBy: String? = null, val takenByName: String? = null, val takenAt: Long? = null,
)

/** A one-time pairing code for another device (1.5.0), shown as a QR code. */
data class PairingCode(val code: String, val name: String, val createdAt: Long, val expiresAt: Long)

data class DeviceInfo(val id: String, val name: String, val kind: String, val pairedAt: Long, val lastSeen: Long?, val open: Boolean, val revokedAt: Long?)

data class ServerStatus(val serverTime: Long, val lastSeq: Long, val lock: LockState, val you: DeviceInfo, val devices: List<DeviceInfo>)

data class Change(val changeId: String, val entityType: String, val entityId: String, val operation: String, val changedFields: JSONObject, val timestamp: Long)

data class ServerChange(
    val seq: Long, val changeId: String, val entityType: String, val entityId: String, val operation: String,
    val changedFields: JSONObject, val timestamp: Long, val deviceId: String,
)

data class Conflict(val entityType: String, val entityId: String, val winnerDevice: String, val loserDevice: String, val at: Long)

data class PushResult(val applied: Int, val duplicates: Int, val lost: Int, val lastSeq: Long, val conflicts: List<Conflict>)

/** [masterReplaced] (server 0.5.1): the master copy was replaced by a restored backup since this device's last pull; it takes the whole copy. */
data class PullResult(val changes: List<ServerChange>, val lastSeq: Long, val more: Boolean, val conflicts: List<Conflict>, val masterReplaced: Boolean = false)

data class Record(val entityType: String, val entityId: String, val data: JSONObject, val deleted: Boolean, val changedAt: Long, val seq: Long)

data class RecordsResult(val records: List<Record>, val more: Boolean)

/**
 * This device's side of the Dead Drop server protocol. The device's secret key never leaves it; the server gets only
 * the public key. Every reply must open with the pinned server key, so a different server is never trusted.
 */
class ServerClient(
    private val transport: Transport,
    private val serverPublic: ByteArray,
    private val keys: Box.KeyPair,
    var deviceId: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ---- before this device is known: setup and login, sealed to the server's key

    fun setup(setupCode: String, username: String, pass: String, name: String, kind: String, recoveryKeyId: String? = null): PairReply =
        account("/v1/setup", JSONObject().put("setupCode", setupCode).put("username", username).put("password", pass)
            .put("name", name).put("kind", kind).put("publicKey", keys.publicKey.hex()).apply { recoveryKeyId?.let { put("recoveryKeyId", it) } })

    fun login(username: String, pass: String, name: String, kind: String): PairReply =
        account("/v1/login", JSONObject().put("username", username).put("password", pass)
            .put("name", name).put("kind", kind).put("publicKey", keys.publicKey.hex()))

    /** Redeems a one-time pairing code (the desktop's QR code) for this device's own key. */
    fun pair(code: String, name: String, kind: String): PairReply =
        account("/v1/pair", JSONObject().put("code", code).put("kind", kind).put("publicKey", keys.publicKey.hex()).put("name", name), PAIR_REFUSED)

    private fun account(path: String, req: JSONObject, refused: String = REFUSED): PairReply {
        val resp = call { transport.post(path, Box.sealed(req.toString().toByteArray(), serverPublic)) }
        when (resp.status) {
            200 -> {}
            403 -> throw ServerException(ServerException.Kind.REFUSED, refused)
            else -> throw ServerException(ServerException.Kind.ERROR, "The server answered ${resp.status}.")
        }
        val plain = Box.openReply(resp.body, serverPublic, keys.secretKey)
            ?: throw ServerException(ServerException.Kind.KEY_CHANGED, KEY_CHANGED)
        val o = JSONObject(String(plain))
        return PairReply(o.getString("deviceId"), o.getString("name"), o.getString("kind"), o.getLong("serverTime"), o.optStringOrNull("recoveryKeyId"))
            .also { deviceId = it.deviceId }
    }

    // ---- the encrypted calls

    private fun rpc(op: String, args: JSONObject = JSONObject()): Any? {
        val id = deviceId ?: throw ServerException(ServerException.Kind.REVOKED, "This device isn't logged in.")
        val plain = JSONObject().put("op", op).put("ts", clock()).put("args", args).toString().toByteArray()
        val (body, nonce) = Box.request(id, plain, serverPublic, keys.secretKey)
        val resp = call { transport.post("/v1/rpc", body) }
        when (resp.status) {
            200 -> {}
            401 -> throw ServerException(ServerException.Kind.REVOKED, REVOKED)
            else -> throw ServerException(ServerException.Kind.ERROR, "The server answered ${resp.status}.")
        }
        val open = Box.openReply(resp.body, serverPublic, keys.secretKey) ?: throw ServerException(ServerException.Kind.KEY_CHANGED, KEY_CHANGED)
        val r = JSONObject(String(open))
        if (r.optString("re") != nonce.hex()) throw ServerException(ServerException.Kind.ERROR, "The server's answer wasn't for this request.")
        if (!r.optBoolean("ok")) throw RpcError(r.optString("error", "error"), r.optString("message", ""))
        return r.opt("result")
    }

    /** The server refused one call (for example "locked": another device holds the edit lock). */
    class RpcError(val code: String, message: String) : Exception(message)

    fun status(): ServerStatus {
        val o = rpc("status") as JSONObject
        return ServerStatus(o.getLong("serverTime"), o.getLong("lastSeq"), lock(o.getJSONObject("lock")), device(o.getJSONObject("you")),
            o.getJSONArray("devices").objects().map(::device))
    }

    fun heartbeat(open: Boolean) = lock(rpc("heartbeat", JSONObject().put("open", open)) as JSONObject)
    fun acquire() = lock(rpc("lock.acquire") as JSONObject)
    fun release() = lock(rpc("lock.release") as JSONObject)
    /** Take over (1.5.0): the lock moves here even while another device holds it. */
    fun takeover() = lock(rpc("lock.takeover") as JSONObject)

    fun pairingCode(name: String, minutes: Long = 10): PairingCode {
        val o = rpc("pairing.new", JSONObject().put("name", name).put("minutes", minutes)) as JSONObject
        return PairingCode(o.getString("code"), o.getString("name"), o.getLong("createdAt"), o.getLong("expiresAt"))
    }

    fun push(changes: List<Change>, baseSeq: Long, offline: Boolean): PushResult {
        val a = JSONArray()
        for (c in changes) a.put(JSONObject().put("changeId", c.changeId).put("entityType", c.entityType).put("entityId", c.entityId)
            .put("operation", c.operation).put("changedFields", c.changedFields).put("timestamp", c.timestamp))
        val o = rpc("push", JSONObject().put("changes", a).put("baseSeq", baseSeq).put("offline", offline)) as JSONObject
        return PushResult(o.getInt("applied"), o.getInt("duplicates"), o.getInt("lost"), o.getLong("lastSeq"), conflicts(o))
    }

    fun pull(sinceSeq: Long, limit: Int = 500): PullResult {
        val o = rpc("pull", JSONObject().put("sinceSeq", sinceSeq).put("limit", limit)) as JSONObject
        val changes = o.getJSONArray("changes").objects().map {
            ServerChange(it.getLong("seq"), it.getString("changeId"), it.getString("entityType"), it.getString("entityId"), it.getString("operation"),
                it.getJSONObject("changedFields"), it.getLong("timestamp"), it.getString("deviceId"))
        }
        return PullResult(changes, o.getLong("lastSeq"), o.getBoolean("more"), conflicts(o), o.optBoolean("masterReplaced", false))
    }

    /**
     * Restore while logged in (1.7.2), before anything changes here: this device holds the edit lock, and the server has written
     * and verified a backup of its current master copy. An older server answers unknown_op.
     */
    fun masterCheck() { rpc("master.check") }

    /** The restored budget as the server's master copy (the server backs up the old one first). Returns the new last seq. */
    fun masterReplace(changes: List<Change>): Long {
        val a = JSONArray()
        for (c in changes) a.put(JSONObject().put("changeId", c.changeId).put("entityType", c.entityType).put("entityId", c.entityId)
            .put("operation", c.operation).put("changedFields", c.changedFields).put("timestamp", c.timestamp))
        val body = JSONObject().put("changes", a)
        if (body.toString().length > MAX_REPLACE_CHARS) throw RpcError("too_large", TOO_BIG)
        return (rpc("master.replace", body) as JSONObject).getLong("lastSeq")
    }

    fun records(afterType: String, afterId: String, limit: Int = 500): RecordsResult {
        val o = rpc("records", JSONObject().put("afterType", afterType).put("afterId", afterId).put("limit", limit)) as JSONObject
        val records = o.getJSONArray("records").objects().map {
            Record(it.getString("entityType"), it.getString("entityId"), it.getJSONObject("data"), it.getBoolean("deleted"), it.getLong("changedAt"), it.getLong("seq"))
        }
        return RecordsResult(records, o.getBoolean("more"))
    }

    fun devices(): List<DeviceInfo> = (rpc("devices.list") as JSONArray).objects().map(::device)

    /** Password change (1.6.0): this logged-in device sets a new one, no old one needed. Other devices stay logged in. */
    fun changePassword(pass: String): Long = (rpc("account.password", JSONObject().put("password", pass)) as JSONObject).getLong("changedAt")

    /** Log out on close (1.6.0): the lock let go and this device removed, in one call. */
    fun logout() { rpc("device.logout") }

    fun recoveryStatus(): RecoveryStatus = recovery(rpc("recovery.status") as JSONObject)

    fun recoverySet(keyId: String): RecoveryStatus = recovery(rpc("recovery.set", JSONObject().put("keyId", keyId)) as JSONObject)

    /** A sealed copy of the account's key for [deviceId], made here with crypto_box_seal to its public key. */
    fun recoveryPut(deviceId: String, keyId: String, sealed: ByteArray) {
        rpc("recovery.put", JSONObject().put("deviceId", deviceId).put("keyId", keyId).put("sealed", sealed.hex()))
    }

    /** The account's key id and this device's sealed copy, if one is waiting. */
    fun recoveryGet(): Pair<String?, ByteArray?> {
        val o = rpc("recovery.get") as JSONObject
        return o.optStringOrNull("keyId") to o.optStringOrNull("sealed")?.unhex()
    }

    private fun recovery(o: JSONObject) = RecoveryStatus(o.optStringOrNull("keyId"), o.optBoolean("mine"),
        (o.optJSONArray("needing") ?: JSONArray()).objects().map { NeedsKey(it.getString("deviceId"), it.optString("name"), it.getString("publicKey").unhex()) })
    fun revoke(id: String): DeviceInfo = device(rpc("devices.revoke", JSONObject().put("deviceId", id)) as JSONObject)

    private fun lock(o: JSONObject) = LockState(o.optStringOrNull("holder"), o.optStringOrNull("holderName"),
        if (o.has("expiresAt") && !o.isNull("expiresAt")) o.getLong("expiresAt") else null, o.optBoolean("youHoldIt"),
        o.optStringOrNull("takenBy"), o.optStringOrNull("takenByName"), if (o.has("takenAt") && !o.isNull("takenAt")) o.getLong("takenAt") else null)

    private fun device(o: JSONObject) = DeviceInfo(o.getString("id"), o.getString("name"), o.getString("kind"), o.getLong("pairedAt"),
        if (o.has("lastSeen") && !o.isNull("lastSeen")) o.getLong("lastSeen") else null, o.optBoolean("open"),
        if (o.has("revokedAt") && !o.isNull("revokedAt")) o.getLong("revokedAt") else null)

    private fun conflicts(o: JSONObject): List<Conflict> = (o.optJSONArray("conflicts") ?: JSONArray()).objects().map {
        Conflict(it.getString("entityType"), it.getString("entityId"), it.getString("winnerDevice"), it.getString("loserDevice"), it.getLong("at"))
    }

    companion object {
        /** The server takes at most 8 MB in one request; a restored budget bigger than this can't go up in one replace. */
        const val MAX_REPLACE_CHARS = 7_000_000
        const val TOO_BIG = "This budget is too big to send to the server in one go (the limit is about 7 MB). Nothing was changed on the server."
        const val KEY_CHANGED = "The server's key is not the one this device pinned. It could be a different server, so nothing was sent. " +
            "If the server was rebuilt on purpose, log out and connect again."
        const val REVOKED = "This device was removed from the server. Log in again to sync."
        const val QR_MISMATCH = "The server's key doesn't match the QR code's fingerprint. It could be a different server, so nothing was sent."
        const val REFUSED = "The server said no. Check the details, and after 5 wrong tries wait 10 minutes."
        const val PAIR_REFUSED = "The server refused that pairing code: it works once, for 10 minutes. Make a new one in the desktop program (Add a phone)."

        /** GET /v1/server-key: the server's public key (public), for pinning on first contact. */
        fun serverKey(transport: Transport): ServerKeyInfo {
            val resp = call { transport.get("/v1/server-key") }
            if (resp.status != 200) throw ServerException(ServerException.Kind.ERROR, "That address answered, but not as a Dead Drop server (${resp.status}).")
            val key = runCatching { JSONObject(String(resp.body)).getString("publicKey").unhex() }.getOrNull()
            if (key == null || key.size != Box.KEY_BYTES) throw ServerException(ServerException.Kind.ERROR, "That address answered, but not as a Dead Drop server.")
            return ServerKeyInfo(key)
        }

        private inline fun call(block: () -> Transport.Response): Transport.Response = try {
            block()
        } catch (e: IOException) {
            throw unreachable(e)
        }

        const val UNREACHABLE = "The server can't be reached."
        const val PLAIN_HTTP_REFUSED = "Not sent: plain http:// only goes to a home network, tailnet or loopback address " +
            "(10.x.x.x, 172.16 to 172.31, 192.168.x.x, 100.64 to 100.127, or 127.x.x.x and ::1), and that address is outside them. " +
            "Use https:// for a server anywhere else."
        const val CLEARTEXT_BLOCKED = "This device refused plain http:// to that address (its security policy). Use the server's " +
            "home network or tailnet address, or https://."
        const val LOCAL_NETWORK_BLOCKED = "Android blocked the connection: Dead Drop Finance isn't allowed to use the local network. " +
            "Allow it (Nearby devices) when the app asks, or in Android's settings under Apps > Dead Drop Finance > Permissions."
        const val DNS_FAILED = "The server's name couldn't be found (DNS). Check the spelling, or type its IP address instead."
        const val REFUSED_CONN = "The connection was refused: that address answered, but nothing is listening on that port. " +
            "Check the port (5090 unless the server uses another) and that the server is running."
        const val TIMED_OUT = "The server didn't answer in time. Check the address, and that this device is on the server's " +
            "network or tailnet."
        const val NO_ROUTE = "There's no route to that address from this device's network. Check the address, and that this " +
            "device is on the server's network or tailnet."
        const val TLS_FAILED = "The https:// connection failed (its certificate or TLS). Check the address, or use http:// on " +
            "the home network."

        /**
         * A network failure in words that say what failed (1.6.0), never with the exception's own text (it can hold the
         * address). Cleartext refusals and local network blocks come first: they are this device's doing, not the server's.
         */
        fun unreachable(e: IOException): ServerException {
            val chain = generateSequence<Throwable>(e) { it.cause }.take(8).toList()
            // by class name, so this file names no networking class (it never opens a connection itself)
            fun has(name: String) = chain.any { t -> generateSequence<Class<*>>(t.javaClass) { it.superclass }.any { it.simpleName == name } }
            val text = chain.joinToString(" ") { it.message ?: "" }
            val (reach, message) = when {
                // Android 17's local network protection: the phone's own check, or a socket refused with EPERM
                chain.any { it is PlainHttpRefused } -> ServerException.Reach.PLAIN_HTTP_REFUSED to PLAIN_HTTP_REFUSED
                chain.any { it is LocalNetworkBlocked } || text.contains("EPERM") || text.contains("Operation not permitted", ignoreCase = true) ->
                    ServerException.Reach.LOCAL_NETWORK_BLOCKED to LOCAL_NETWORK_BLOCKED
                // Android: UnknownServiceException "CLEARTEXT communication to ... not permitted by network security policy"
                has("UnknownServiceException") || text.contains("CLEARTEXT", ignoreCase = true) ->
                    ServerException.Reach.CLEARTEXT_BLOCKED to CLEARTEXT_BLOCKED
                has("UnknownHostException") -> ServerException.Reach.DNS to DNS_FAILED
                has("SocketTimeoutException") || text.contains("timed out", ignoreCase = true) ->
                    ServerException.Reach.TIMEOUT to TIMED_OUT
                has("NoRouteToHostException") || text.contains("unreachable", ignoreCase = true) ->
                    ServerException.Reach.NO_ROUTE to NO_ROUTE
                has("ConnectException") -> ServerException.Reach.REFUSED to REFUSED_CONN
                has("SSLException") -> ServerException.Reach.TLS to TLS_FAILED
                else -> ServerException.Reach.OTHER to UNREACHABLE
            }
            val kind = if (reach == ServerException.Reach.CLEARTEXT_BLOCKED || reach == ServerException.Reach.PLAIN_HTTP_REFUSED) ServerException.Kind.ERROR
            else ServerException.Kind.UNREACHABLE
            return ServerException(kind, message, reach)
        }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
