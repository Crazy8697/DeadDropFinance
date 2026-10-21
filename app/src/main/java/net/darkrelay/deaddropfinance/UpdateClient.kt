package net.darkrelay.deaddropfinance

import java.net.HttpURLConnection
import java.net.URL

/**
 * The one network call the app makes: an anonymous GET of GitHub's releases/latest. Plain JVM (no Android
 * classes), so it can be tested against a local server. Nothing is sent but the request itself: a User-Agent
 * naming the app and its version, and an Accept header. No IDs, nothing about the phone or the budget. Every
 * failure ends as Result.Failed; this never throws.
 */
object UpdateClient {

    const val CONNECT_TIMEOUT_MS = 5000
    const val READ_TIMEOUT_MS = 8000
    private const val MAX_BODY = 512 * 1024

    fun userAgent(version: String) = "DeadDropFinance/$version (+https://darkrelay.net)"

    fun check(
        url: String,
        installed: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): UpdateCheck.Result = try {
        fetch(url, installed, connectTimeoutMs, readTimeoutMs)
    } catch (t: Throwable) {
        UpdateCheck.Result.Failed
    }

    private fun fetch(url: String, installed: String, connectTimeoutMs: Int, readTimeoutMs: Int): UpdateCheck.Result {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent(installed))
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return UpdateCheck.Result.Failed
            val body = conn.inputStream.use { input ->
                val bytes = input.readNBytes(MAX_BODY + 1)
                if (bytes.size > MAX_BODY) return UpdateCheck.Result.Failed
                String(bytes, Charsets.UTF_8)
            }
            return UpdateCheck.evaluate(body, installed)
        } finally {
            conn.disconnect()
        }
    }
}
