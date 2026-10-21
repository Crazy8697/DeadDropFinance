package net.darkrelay.deaddropfinance.sync

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import javax.net.ssl.SSLException
import net.darkrelay.deaddropfinance.data.Provider
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Teller (api.teller.io): mutual TLS with the user's own application certificate, plus the enrollment's access
 * token as the HTTP Basic username with an empty password. Teller documents no fixed rate limit for this tier;
 * a 429 is retried with backoff by [Http].
 */
class TellerSource(
    accessToken: String,
    private val http: Http,
    private val baseUrl: String = BASE_URL,
) : SyncSource {

    override val provider = Provider.TELLER

    /** Teller's docs advise re-reading 7 to 10 days past the last sync, since pending rows can change when they post. */
    override val overlapDays = 10L

    private val auth = mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$accessToken:".toByteArray()))

    override suspend fun accounts(): FetchResult {
        val list = array(get("$baseUrl/accounts"))
        val out = mutableListOf<AccountData>()
        for (i in 0 until list.length()) {
            val a = list.optJSONObject(i) ?: continue
            if (a.optString("status") == "closed") continue
            out += AccountData(account(a, balance(a)), emptyList())
        }
        return FetchResult(out)
    }

    override suspend fun fetch(wanted: Map<String, LocalDate>, to: LocalDate): FetchResult {
        val list = array(get("$baseUrl/accounts"))
        val out = mutableListOf<AccountData>()
        for (i in 0 until list.length()) {
            val a = list.optJSONObject(i) ?: continue
            val id = a.optString("id")
            val from = wanted[id] ?: continue
            if (a.optString("status") == "closed") continue
            val txns = transactions(id, from, to)
            out += AccountData(account(a, balance(a)), txns)
        }
        return FetchResult(out)
    }

    private fun account(a: JSONObject, balance: Long?): ProviderAccount {
        val inst = a.optJSONObject("institution")?.optString("name").orEmpty()
        val name = a.optString("name").ifEmpty { "Account" }
        return ProviderAccount(a.optString("id"), if (inst.isNotEmpty() && !name.contains(inst, true)) "$inst $name" else name, a.optString("last_four"), balance)
    }

    /**
     * The ledger balance, signed like the app (money you have is positive). For a credit account Teller reports
     * what is owed, so it's turned negative. Teller's docs don't spell this sign out; the balance check on the
     * account screen shows any difference instead of trusting it.
     */
    private suspend fun balance(a: JSONObject): Long? {
        val link = a.optJSONObject("links")?.optString("balances").orEmpty()
        if (link.isEmpty()) return null
        val b = try { JSONObject(get("$baseUrl/accounts/${a.optString("id")}/balances")) } catch (e: JSONException) { throw SyncError.BadData("Teller sent a balance that isn't readable.") }
        val cents = Cents.parse(b.optString("ledger", "").ifEmpty { b.optString("available", "") }) ?: return null
        return if (a.optString("type") == "credit") -cents else cents
    }

    private suspend fun transactions(accountId: String, from: LocalDate, to: LocalDate): List<ProviderTxn> {
        val out = LinkedHashMap<String, ProviderTxn>()
        var fromId: String? = null
        while (true) {
            val q = "start_date=$from&end_date=$to&count=$PAGE" + (fromId?.let { "&from_id=$it" } ?: "")
            val page = array(get("$baseUrl/accounts/$accountId/transactions?$q"))
            for (i in 0 until page.length()) {
                val t = page.optJSONObject(i) ?: continue
                val id = t.optString("id").ifEmpty { continue }
                val date = try { LocalDate.parse(t.optString("date")) } catch (e: DateTimeParseException) { throw SyncError.BadData("Teller sent a date that isn't readable.") }
                val amount = Cents.parse(t.optString("amount", "")) ?: throw SyncError.BadData("Teller sent an amount that isn't a number.")
                out[id] = ProviderTxn(id, date, amount, t.optString("description").trim(), t.optString("status") == "pending")
            }
            if (page.length() < PAGE) break
            val last = page.optJSONObject(page.length() - 1)?.optString("id")
            if (last.isNullOrEmpty() || last == fromId) break
            fromId = last
        }
        return out.values.toList()
    }

    private fun array(body: String): JSONArray = try { JSONArray(body) } catch (e: JSONException) { throw SyncError.BadData("Teller sent something that isn't readable.") }

    private suspend fun get(url: String): String {
        val r = try {
            http.get(url, auth)
        } catch (e: SSLException) {
            throw SyncError.NeedsFix(FixCode.TELLER_CERTIFICATE, "Teller didn't accept your certificate. Import the certificate and private key from your Teller dashboard again.")
        }
        if (r.status in 200..299) return r.body
        val code = try { JSONObject(r.body).optJSONObject("error")?.optString("code").orEmpty() } catch (e: JSONException) { "" }
        throw errorFor(r.status, code)
    }

    companion object {
        const val BASE_URL = "https://api.teller.io"
        const val PAGE = 250

        fun errorFor(status: Int, code: String): SyncError = when {
            status == 400 -> SyncError.NeedsFix(FixCode.TELLER_CERTIFICATE, "Teller needs your certificate for this. Import the certificate and private key from your Teller dashboard.")
            status == 401 || status == 403 -> SyncError.NeedsFix(FixCode.TELLER_RECONNECT, "Teller no longer accepts this bank connection. Reconnect the bank.")
            status == 404 && code.startsWith("enrollment.disconnected") -> SyncError.NeedsFix(FixCode.TELLER_RECONNECT, reconnectReason(code))
            status == 429 -> SyncError.RateLimited("Teller asked us to slow down.")
            status >= 500 -> SyncError.Network("Teller or the bank isn't answering right now.")
            else -> SyncError.BadData("Teller answered with an error ($status).")
        }

        private fun reconnectReason(code: String) = when (code.removePrefix("enrollment.disconnected").trimStart('.')) {
            "credentials_invalid" -> "Your bank login changed. Reconnect the bank to keep syncing."
            "account_locked" -> "Your bank has locked access to the account. Sort it out with the bank, then reconnect."
            "enrollment_inactive" -> "The bank connection went quiet and was closed. Reconnect the bank to keep syncing."
            "user_action.mfa_required" -> "Your bank wants a security code. Reconnect the bank to enter it."
            "user_action.captcha_required" -> "Your bank wants a check that you're a person. Reconnect the bank to do it."
            "user_action.web_login_required" -> "Your bank wants you to log in on its website first (for example to accept new terms). Then reconnect here."
            "user_action.contact_information_required" -> "Your bank wants you to update your contact details on its website. Then reconnect here."
            "user_action.insufficient_permissions" -> "Your bank login doesn't have permission to see this account."
            else -> "The bank connection was lost. Reconnect the bank to keep syncing."
        }
    }
}
