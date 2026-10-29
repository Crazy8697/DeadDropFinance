package net.darkrelay.deaddropfinance.serversync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Plain HTTP to the user's own Dead Drop server (on their network). Plain is fine: every request and reply except the
 * server's public key is a libsodium envelope (Box), so the network sees only ciphertext. Nothing else is sent: no
 * cookies, no IDs beyond the envelope's device id.
 */
class HttpTransport(baseUrl: String, private val connectTimeoutMs: Int = 8_000, private val readTimeoutMs: Int = 30_000) : Transport {
    val base: String = normalize(baseUrl)

    override fun get(path: String): Transport.Response = send("GET", path, null)

    override fun post(path: String, body: ByteArray): Transport.Response = send("POST", path, body)

    private fun send(method: String, path: String, body: ByteArray?): Transport.Response {
        val conn = URI.create(base + path).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.requestMethod = method
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { input ->
                val b = input.readNBytes(MAX_BODY + 1)
                if (b.size > MAX_BODY) throw IOException("reply too large")
                b
            } ?: ByteArray(0)
            return Transport.Response(status, bytes)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MAX_BODY = 9 * 1024 * 1024
        const val DEFAULT_PORT = 5090

        /** "host", "host:5090" or "http://host:port/" all become "http://host:port" (5090 when no port is given). */
        fun normalize(address: String): String {
            var a = address.trim().trimEnd('/')
            require(a.isNotEmpty()) { "Type the server's address." }
            if (!a.contains("://")) a = "http://$a"
            val u = runCatching { URI(a) }.getOrNull()
            require(u != null && u.host != null && (u.scheme == "http" || u.scheme == "https") && (u.path.isNullOrEmpty())) { "That isn't a server address." }
            val port = if (u.port > 0) u.port else if (u.scheme == "https") 443 else DEFAULT_PORT
            return "${u.scheme}://${u.host}:$port"
        }
    }
}
