package net.darkrelay.deaddropfinance.serversync

import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * An in-memory Dead Drop server for the unit tests: no network at all. It speaks the real envelopes (Box, real
 * libsodium, test keys) and follows the server's rules (DeadDropServer's Service.kt): one account made with a one-time
 * setup code, login, 5 failures lock, push needs the edit lock unless offline, last change wins by its time, pull,
 * records, devices and revoke. [online] false makes every call fail as an unreachable server does.
 */
class FakeServer(var clock: () -> Long = System::currentTimeMillis, val lockTimeoutMs: Long = 120_000) {
    val keys: Box.KeyPair = Box.keyPair()
    var online = true
    var setupCode: String? = "TEST-CODE"
    var account: Pair<String, String>? = null
    private var failures = 0

    class Device(val id: String, val name: String, val kind: String, var key: ByteArray?, val pairedAt: Long, var revokedAt: Long? = null, var open: Boolean = false)
    val devices = linkedMapOf<String, Device>()

    class Row(val seq: Long, val change: JSONObject, val device: String, val offline: Boolean)
    val changes = mutableListOf<Row>()
    class Rec(var data: JSONObject, var deleted: Boolean, var changedAt: Long, var changedBy: String, var seq: Long)
    val records = sortedMapOf<String, Rec>()        // "type\u0000id"
    private val applied = mutableSetOf<String>()
    class Conf(val type: String, val id: String, val winner: String, val loser: String, val at: Long)
    val conflicts = mutableListOf<Conf>()
    var lockHolder: String? = null
    var lockExpires = 0L

    fun transport(): Transport = object : Transport {
        override fun get(path: String): Transport.Response {
            if (!online) throw IOException("offline")
            return if (path == "/v1/server-key") Transport.Response(200, JSONObject().put("publicKey", keys.publicKey.hex()).put("fingerprint", Box.fingerprint(keys.publicKey)).toString().toByteArray())
            else Transport.Response(404, ByteArray(0))
        }

        override fun post(path: String, body: ByteArray): Transport.Response {
            if (!online) throw IOException("offline")
            return synchronized(this@FakeServer) {
                when (path) {
                    "/v1/setup", "/v1/login" -> account(path, body)
                    "/v1/rpc" -> rpc(body)
                    else -> Transport.Response(404, ByteArray(0))
                }
            }
        }
    }

    private fun account(path: String, body: ByteArray): Transport.Response {
        val forbidden = Transport.Response(403, ByteArray(0))
        if (failures >= 5) return forbidden
        val plain = if (body.isNotEmpty() && body[0] == Box.VERSION) Box.sealOpen(body.copyOfRange(1, body.size), keys.publicKey, keys.secretKey) else null
        val req = plain?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: run { failures++; return forbidden }
        val key = runCatching { req.getString("publicKey").unhex() }.getOrNull()
        val ok = when (path) {
            "/v1/setup" -> setupCode != null && account == null && req.getString("setupCode").uppercase().filter { it.isLetterOrDigit() } ==
                setupCode!!.filter { it.isLetterOrDigit() } && req.getString("password").length >= 8
            else -> account != null && account!!.first.equals(req.getString("username").trim(), true) && account!!.second == req.getString("password")
        }
        if (!ok || key == null || key.size != 32) { failures++; return forbidden }
        if (path == "/v1/setup") { account = req.getString("username").trim() to req.getString("password"); setupCode = null }
        val id = UUID.randomUUID().toString()
        devices[id] = Device(id, req.getString("name"), req.getString("kind"), key, clock())
        val reply = JSONObject().put("deviceId", id).put("name", req.getString("name")).put("kind", req.getString("kind")).put("serverTime", clock())
        return Transport.Response(200, Box.reply(reply.toString().toByteArray(), key, keys.secretKey))
    }

    private fun rpc(body: ByteArray): Transport.Response {
        val b = ByteBuffer.wrap(body, 1, body.size - 1)
        val id = UUID(b.getLong(), b.getLong()).toString()
        val nonce = ByteArray(24).also { b.get(it) }
        val cipher = ByteArray(b.remaining()).also { b.get(it) }
        val dev = devices[id]?.takeIf { it.key != null } ?: return Transport.Response(401, ByteArray(0))
        val plain = Box.open(cipher, nonce, dev.key!!, keys.secretKey) ?: return Transport.Response(401, ByteArray(0))
        val req = JSONObject(String(plain))
        val resp = JSONObject().put("re", nonce.hex())
        try {
            resp.put("ok", true).put("result", dispatch(id, req.getString("op"), req.getJSONObject("args")))
        } catch (e: Refused) {
            resp.put("ok", false).put("error", e.code).put("message", e.message)
        }
        return Transport.Response(200, Box.reply(resp.toString().toByteArray(), dev.key!!, keys.secretKey))
    }

    class Refused(val code: String, message: String) : Exception(message)

    private fun holder(): String? = lockHolder?.takeIf { lockExpires > clock() }

    private fun lockJson(me: String): JSONObject {
        val h = holder() ?: return JSONObject().put("youHoldIt", false)
        return JSONObject().put("holder", h).put("holderName", devices[h]?.name).put("expiresAt", lockExpires).put("youHoldIt", h == me)
    }

    private fun devJson(d: Device) = JSONObject().put("id", d.id).put("name", d.name).put("kind", d.kind).put("pairedAt", d.pairedAt)
        .put("open", d.open).put("revokedAt", d.revokedAt ?: JSONObject.NULL)

    private fun lastSeq() = changes.lastOrNull()?.seq ?: 0L

    private fun dispatch(me: String, op: String, a: JSONObject): Any = when (op) {
        "status" -> JSONObject().put("serverTime", clock()).put("lastSeq", lastSeq()).put("lock", lockJson(me))
            .put("you", devJson(devices[me]!!)).put("devices", JSONArray(devices.values.map(::devJson)))
        "heartbeat" -> {
            devices[me]!!.open = a.optBoolean("open", true)
            if (holder() == me) { if (a.optBoolean("open", true)) lockExpires = clock() + lockTimeoutMs else lockHolder = null }
            lockJson(me)
        }
        "lock.acquire" -> { if (holder() == null || holder() == me) { lockHolder = me; lockExpires = clock() + lockTimeoutMs }; lockJson(me) }
        "lock.release" -> { if (holder() == me) lockHolder = null; lockJson(me) }
        "push" -> push(me, a)
        "pull" -> {
            val since = a.getLong("sinceSeq"); val limit = a.optInt("limit", 500)
            val rows = changes.filter { it.seq > since }
            val page = rows.take(limit)
            JSONObject().put("changes", JSONArray(page.map { JSONObject(it.change.toString()).put("seq", it.seq).put("deviceId", it.device).put("offline", it.offline) }))
                .put("lastSeq", page.lastOrNull()?.seq ?: lastSeq()).put("more", rows.size > limit)
                .put("conflicts", JSONArray(conflicts.filter { it.loser == me }.map(::confJson)))
        }
        "records" -> {
            val after = a.getString("afterType") + "\u0000" + a.getString("afterId"); val limit = a.optInt("limit", 500)
            val rows = records.entries.filter { it.key > after }
            JSONObject().put("records", JSONArray(rows.take(limit).map { (k, r) ->
                JSONObject().put("entityType", k.substringBefore("\u0000")).put("entityId", k.substringAfter("\u0000")).put("data", r.data)
                    .put("deleted", r.deleted).put("changedAt", r.changedAt).put("changedBy", r.changedBy).put("seq", r.seq)
            })).put("more", rows.size > limit)
        }
        "devices.list" -> JSONArray(devices.values.map(::devJson))
        "devices.revoke" -> {
            val d = devices[a.getString("deviceId")]?.takeIf { it.revokedAt == null } ?: throw Refused("not_found", "no active device with that id")
            d.key = null; d.revokedAt = clock(); d.open = false
            if (lockHolder == d.id) lockHolder = null
            devJson(d)
        }
        else -> throw Refused("unknown_op", "unknown op $op")
    }

    private fun confJson(c: Conf) = JSONObject().put("entityType", c.type).put("entityId", c.id).put("winnerDevice", c.winner).put("loserDevice", c.loser)
        .put("note", "last change wins").put("at", c.at)

    private fun push(me: String, a: JSONObject): JSONObject {
        val offline = a.optBoolean("offline", false)
        if (!offline && holder() != me) throw Refused("locked", if (holder() == null) "take the edit lock first" else "another device holds the edit lock")
        val baseSeq = a.optLong("baseSeq", 0)
        var appliedN = 0; var dup = 0; var lost = 0
        val confs = JSONArray()
        for (c in a.getJSONArray("changes").objects()) {
            val cid = c.getString("changeId")
            if (cid in applied) { dup++; continue }
            val key = c.getString("entityType") + "\u0000" + c.getString("entityId")
            val existing = records[key]
            val ts = c.getLong("timestamp")
            if (existing != null && ts < existing.changedAt) {
                applied += cid; lost++
                if (existing.changedBy != me) conflicts += Conf(c.getString("entityType"), c.getString("entityId"), existing.changedBy, me, clock()).also { confs.put(confJson(it)) }
                continue
            }
            if (existing != null && existing.changedBy != me && existing.seq > baseSeq) {
                conflicts += Conf(c.getString("entityType"), c.getString("entityId"), me, existing.changedBy, clock()).also { confs.put(confJson(it)) }
            }
            val fields = c.getJSONObject("changedFields")
            val merged = JSONObject(existing?.data?.toString() ?: "{}").also { m -> for (k in fields.keys()) m.put(k, fields.get(k)) }
            val deleted = if (c.getString("operation") == "DELETE") true else if (fields.has("deleted")) fields.optBoolean("deleted") else existing?.deleted ?: false
            val seq = lastSeq() + 1
            changes += Row(seq, c, me, offline)
            records[key] = Rec(merged, deleted, ts, me, seq)
            applied += cid; appliedN++
        }
        return JSONObject().put("applied", appliedN).put("duplicates", dup).put("lost", lost).put("lastSeq", lastSeq()).put("conflicts", confs)
    }

    /** A device that isn't a phone under test: pushes straight in, as another device would. */
    fun otherDevice(name: String): String {
        val id = UUID.randomUUID().toString()
        devices[id] = Device(id, name, "desktop", Box.keyPair().publicKey, clock())
        return id
    }

    fun pushAs(device: String, changes: List<JSONObject>, offline: Boolean = true) =
        synchronized(this) { push(device, JSONObject().put("changes", JSONArray(changes)).put("offline", offline)) }

    fun lockAs(device: String) = synchronized(this) { lockHolder = device; lockExpires = clock() + lockTimeoutMs }
}
