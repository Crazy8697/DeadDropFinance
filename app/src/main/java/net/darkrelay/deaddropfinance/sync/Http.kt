package net.darkrelay.deaddropfinance.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class HttpResult(val status: Int, val body: String)

/**
 * A small HTTP client for the providers. Rate limits (429), server trouble (502 to 504) and network errors are
 * retried silently with backoff; after the last attempt they surface as [SyncError.RateLimited] or
 * [SyncError.Network]. Nothing about a request or response is ever logged.
 */
class Http(
    private val sslFactory: SSLSocketFactory? = null,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = 3,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val baseBackoffMs: Long = 1_000,
) {
    /** [beforeEach] runs before every attempt, retries included (a request limiter counts each one that goes out). */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), beforeEach: (suspend () -> Unit)? = null) = call("GET", url, headers, maxAttempts, beforeEach)

    /** [retry] false: one attempt only, for a request that must never be sent twice (claiming a one-time token). */
    suspend fun post(url: String, headers: Map<String, String> = emptyMap(), retry: Boolean = true) = call("POST", url, headers, if (retry) maxAttempts else 1, null)

    private suspend fun call(method: String, url: String, headers: Map<String, String>, attempts: Int, beforeEach: (suspend () -> Unit)?): HttpResult {
        var lastIssue: SyncError? = null
        for (attempt in 1..attempts) {
            beforeEach?.invoke()
            try {
                val r = withContext(Dispatchers.IO) { once(method, url, headers) }
                when (r.status) {
                    429 -> lastIssue = SyncError.RateLimited("The provider asked us to slow down.")
                    502, 503, 504 -> lastIssue = SyncError.Network("The provider's service is unavailable right now.")
                    else -> return r
                }
            } catch (e: javax.net.ssl.SSLException) {
                throw e                                   // a TLS or certificate problem is not a transient one
            } catch (e: IOException) {
                lastIssue = SyncError.Network("Couldn't reach the provider.")
            }
            if (attempt < attempts) sleep(baseBackoffMs * (1L shl (attempt - 1)))
        }
        throw lastIssue ?: SyncError.Network("Couldn't reach the provider.")
    }

    private fun once(method: String, url: String, headers: Map<String, String>): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            if (c is HttpsURLConnection && sslFactory != null) c.sslSocketFactory = sslFactory
            c.requestMethod = method
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "DeadDropFinance")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (method == "POST") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val body = stream?.use { s ->
                val bytes = s.readNBytes(MAX_BODY + 1)
                if (bytes.size > MAX_BODY) throw IOException("response too large")
                String(bytes, Charsets.UTF_8)
            } ?: ""
            return HttpResult(status, body)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val MAX_BODY = 20 * 1024 * 1024
    }
}
