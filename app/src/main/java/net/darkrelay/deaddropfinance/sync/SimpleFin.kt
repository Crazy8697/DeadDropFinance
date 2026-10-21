package net.darkrelay.deaddropfinance.sync

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import net.darkrelay.deaddropfinance.data.Provider
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * SimpleFIN Bridge (protocol v2): claim a one-time setup token for an access URL, then read /accounts.
 * Only HTTPS is used and certificates are verified (the platform default). The access URL carries the login, so it
 * is a secret: it is kept in SecretStore and never logged or shown.
 */
class SimpleFinSource(
    accessUrl: String,
    private val http: Http = Http(),
    private val limiter: RequestLimiter? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    allowLoopbackHttp: Boolean = false,
) : SyncSource {

    override val provider = Provider.SIMPLEFIN

    /** The Bridge's developer notes recommend overlapping fetch windows by about 5 days. */
    override val overlapDays = 5L

    /** The Bridge allows at most 90 days between start-date and end-date. */
    override val maxRangeDays = WINDOW_DAYS

    private val parsed = parseAccessUrl(accessUrl, allowLoopbackHttp)

    override suspend fun accounts(): FetchResult {
        val (rows, messages) = request(listOf("balances-only" to "1"))
        return FetchResult(rows.map { AccountData(it.account, emptyList(), it.complete) }, messages)
    }

    override suspend fun fetch(wanted: Map<String, LocalDate>, to: LocalDate): FetchResult {
        if (wanted.isEmpty()) return FetchResult(emptyList())
        val start = wanted.values.min()
        val messages = linkedSetOf<String>()
        val byAccount = LinkedHashMap<String, MutableList<ProviderTxn>>()
        val accountsSeen = LinkedHashMap<String, ProviderAccount>()
        val incomplete = mutableSetOf<String>()
        // the Bridge allows at most 90 days between start-date and end-date, so a long history goes in windows
        var from = start
        val last = to.plusDays(1)                              // end-date is exclusive
        while (from.isBefore(last)) {
            val until = minOf(from.plusDays(WINDOW_DAYS), last)
            val (rows, msgs) = request(
                listOf(
                    "start-date" to from.atStartOfDay(zone).toEpochSecond().toString(),
                    "end-date" to until.atStartOfDay(zone).toEpochSecond().toString(),
                    "pending" to "1",
                ),
            )
            messages += msgs
            for (r in rows) {
                accountsSeen[r.account.id] = r.account
                if (!r.complete) incomplete += r.account.id
                byAccount.getOrPut(r.account.id) { mutableListOf() }.addAll(r.transactions)
            }
            from = until
        }
        val out = accountsSeen.values.filter { it.id in wanted }.map { a ->
            val from0 = wanted.getValue(a.id)
            val txns = byAccount[a.id].orEmpty().filter { !it.date.isBefore(from0) }.distinctBy { it.id }
            AccountData(a, txns, complete = a.id !in incomplete)
        }
        return FetchResult(out, messages.toList())
    }

    class Row(val account: ProviderAccount, val transactions: List<ProviderTxn>, val complete: Boolean)

    private suspend fun request(params: List<Pair<String, String>>): Pair<List<Row>, List<String>> {
        limiter?.take()
        val query = (listOf("version" to "2") + params).joinToString("&") { (k, v) -> "$k=$v" }
        val r = http.get("${parsed.base}/accounts?$query", mapOf("Authorization" to parsed.basicAuth))
        when (r.status) {
            200 -> {}
            402 -> throw SyncError.NeedsFix(FixCode.SIMPLEFIN_PAYMENT, "SimpleFIN says payment is required. Check your SimpleFIN Bridge subscription.")
            403 -> throw SyncError.NeedsFix(FixCode.SIMPLEFIN_TOKEN, "SimpleFIN no longer accepts this connection. Get a new setup token from the Bridge and connect again.")
            else -> throw SyncError.Network("SimpleFIN answered with an error (${r.status}).")
        }
        return parseAccountSet(r.body, zone)
    }

    class Parsed(val base: String, val basicAuth: String)

    companion object {
        const val WINDOW_DAYS = 89L

        private fun secure(u: URI, allowLoopbackHttp: Boolean) =
            u.scheme.equals("https", true) ||
                (allowLoopbackHttp && u.scheme.equals("http", true) && u.host in setOf("127.0.0.1", "localhost", "[::1]"))

        fun parseAccessUrl(url: String, allowLoopbackHttp: Boolean = false): Parsed {
            val u = try { URI(url.trim()) } catch (e: Exception) { throw SyncError.BadData("The saved SimpleFIN address can't be read.") }
            if (u.scheme == null || u.host == null || !secure(u, allowLoopbackHttp)) throw SyncError.BadData("SimpleFIN addresses must be secure (https).")
            val info = u.rawUserInfo ?: throw SyncError.BadData("The saved SimpleFIN address has no login in it.")
            val port = if (u.port >= 0) ":${u.port}" else ""
            val path = (u.rawPath ?: "").trimEnd('/')
            val auth = "Basic " + Base64.getEncoder().encodeToString(java.net.URLDecoder.decode(info, "UTF-8").toByteArray(Charsets.UTF_8))
            return Parsed("${u.scheme}://${u.host}$port$path", auth)
        }

        /**
         * Claims a setup token once: POST to the address inside the token, and the answer is the access URL.
         * A 403 means the token is wrong or already used: the Bridge says that could mean it was stolen.
         */
        suspend fun claim(setupToken: String, http: Http = Http(), allowLoopbackHttp: Boolean = false): String {
            val claimUrl = Paste.decodeClaimUrl(setupToken.trim())
                ?: throw SyncError.BadData("That doesn't decode to a SimpleFIN token.")
            val u = try { URI(claimUrl) } catch (e: Exception) { throw SyncError.BadData("That token has an address that can't be read.") }
            if (u.host == null || !secure(u, allowLoopbackHttp)) throw SyncError.BadData("That token points somewhere that isn't a secure address, so it isn't used.")
            val r = http.post(claimUrl)
            when (r.status) {
                200 -> {}
                403 -> throw SyncError.NeedsFix(
                    FixCode.SIMPLEFIN_TOKEN,
                    "SimpleFIN says that token is already used or doesn't exist. Setup tokens work once. If you didn't use it, someone else may have: " +
                        "disable it in your SimpleFIN account, then get a new setup token.",
                )
                else -> throw SyncError.Network("SimpleFIN answered with an error (${r.status}).")
            }
            val access = r.body.trim()
            parseAccessUrl(access, allowLoopbackHttp)          // must be a secure address with a login
            return access
        }

        /** Strips markup and control characters from text the Bridge sent, then trims it. Shown, never trusted. */
        fun sanitize(s: String): String = s.replace(Regex("<[^>]*>"), " ").filter { !it.isISOControl() || it == ' ' }
            .replace(Regex("\\s+"), " ").trim().take(300)

        fun parseAccountSet(body: String, zone: ZoneId): Pair<List<Row>, List<String>> {
            val root = try { JSONObject(body) } catch (e: JSONException) { throw SyncError.BadData("SimpleFIN sent something that isn't readable.") }
            val messages = mutableListOf<String>()
            val failedAccounts = mutableSetOf<String>()
            val errlist = root.optJSONArray("errlist")
            if (errlist != null) {
                for (i in 0 until errlist.length()) {
                    val e = errlist.optJSONObject(i) ?: continue
                    val code = e.optString("code")
                    val acct = e.optString("account_id", "")
                    if (code.startsWith("act.") && acct.isNotEmpty()) failedAccounts += acct
                    if (code == "gen.api" || code.startsWith("gen.api.")) continue      // for developers, not for the user
                    sanitize(e.optString("msg")).takeIf { it.isNotEmpty() }?.let { messages += it }
                }
            } else {
                val old = root.optJSONArray("errors")
                if (old != null) for (i in 0 until old.length()) sanitize(old.optString(i)).takeIf { it.isNotEmpty() }?.let { messages += it }
            }
            val connNames = HashMap<String, String>()
            root.optJSONArray("connections")?.let { cs ->
                for (i in 0 until cs.length()) cs.optJSONObject(i)?.let { connNames[it.optString("conn_id")] = sanitize(it.optString("name")) }
            }
            val accounts = root.optJSONArray("accounts") ?: JSONArray()
            val rows = mutableListOf<Row>()
            for (i in 0 until accounts.length()) {
                val a = accounts.optJSONObject(i) ?: continue
                val id = a.optString("id").ifEmpty { throw SyncError.BadData("SimpleFIN sent an account without an id.") }
                val balance = Cents.parse(a.optString("balance", ""))
                val balanceDate = a.optLong("balance-date", 0).takeIf { it > 0 }
                val label = sanitize(a.optString("name")).ifEmpty { "Account" }
                val bank = connNames[a.optString("conn_id")].orEmpty()
                val name = if (bank.isNotEmpty() && !label.contains(bank, true)) "$bank $label" else label
                val txns = mutableListOf<ProviderTxn>()
                a.optJSONArray("transactions")?.let { ts ->
                    for (j in 0 until ts.length()) {
                        val t = ts.optJSONObject(j) ?: continue
                        val tid = t.optString("id").ifEmpty { continue }
                        val amount = Cents.parse(t.optString("amount", "")) ?: throw SyncError.BadData("SimpleFIN sent a transaction amount that isn't a number.")
                        val pending = t.optBoolean("pending", false)
                        val posted = t.optLong("posted", 0)
                        val transacted = t.optLong("transacted_at", 0)
                        val epoch = if (posted > 0) posted else if (transacted > 0) transacted else Instant.now().epochSecond
                        txns += ProviderTxn(tid, Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate(), amount, sanitize(t.optString("description")), pending)
                    }
                }
                rows += Row(ProviderAccount(id, name, mask = "", balance = balance, balanceDate = balanceDate), txns, complete = id !in failedAccounts)
            }
            return rows to messages
        }
    }
}
