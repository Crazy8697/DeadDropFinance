package net.darkrelay.deaddropfinance.serversync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Plain HTTP to the user's own Dead Drop server (on their network). Plain is fine: every request and reply except the
 * server's public key is a libsodium envelope (Box), so the network sees only ciphertext. Nothing else is sent: no
 * cookies, no IDs beyond the envelope's device id.
 *
 * Plain http:// goes only to a home network, a tailnet or this computer (1.6.0): after the name lookup, every address
 * the host has must be in the home network ranges (10/8, 172.16/12, 192.168/16), the tailnet range (100.64/10) or
 * loopback (127/8, ::1), or
 * the call is refused before anything is sent (PlainHttpRefused). https:// goes anywhere.
 */
class HttpTransport(baseUrl: String, private val connectTimeoutMs: Int = 8_000, private val readTimeoutMs: Int = 30_000) : Transport {
    val base: String = normalize(baseUrl)

    override fun get(path: String): Transport.Response = send("GET", path, null)

    override fun post(path: String, body: ByteArray): Transport.Response = send("POST", path, body)

    private fun send(method: String, path: String, body: ByteArray?): Transport.Response {
        val uri = URI.create(base + path)
        checkBeforeSending(uri)
        val conn = uri.toURL().openConnection() as HttpURLConnection
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

        /** Plain http:// must pass [checkPlainHttp]; https:// goes anywhere. */
        internal fun checkBeforeSending(uri: URI) { if (uri.scheme == "http") checkPlainHttp(uri.host) }

        /** Looks [host] up (a literal address needs no lookup) and refuses plain HTTP when any address is outside the allowed ranges. */
        fun checkPlainHttp(host: String) {
            val addrs = InetAddress.getAllByName(host.removePrefix("[").removeSuffix("]"))
            if (addrs.isEmpty() || !addrs.all(::plainHttpAllowed)) throw PlainHttpRefused()
        }

        /** 10/8, 172.16/12, 192.168/16, 100.64/10, 127/8 and ::1. */
        fun plainHttpAllowed(a: InetAddress): Boolean {
            if (a is Inet6Address) return a.isLoopbackAddress
            if (a !is Inet4Address) return false
            val b = a.address.map { it.toInt() and 0xff }
            return b[0] == 10 || b[0] == 127 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 100 && b[1] in 64..127)
        }

        /**
         * "host", "host:5090", "http://host:port" and "https://host:port" (spaces around it and trailing slashes
         * trimmed, the scheme in any case) become "scheme://host:port": 5090 when no port is given, 443 for https.
         */
        fun normalize(address: String): String {
            val t = address.trim()
            require(t.trimEnd('/').isNotEmpty()) { "Type the server's address." }
            val scheme = if (t.contains("://")) t.substringBefore("://").trim().lowercase() else "http"
            val rest = (if (t.contains("://")) t.substringAfter("://") else t).trimEnd('/').trim()
            val a = "$scheme://$rest"
            val u = runCatching { URI(a) }.getOrNull()
            require(
                u != null && u.host != null && (u.scheme == "http" || u.scheme == "https") && u.path.isNullOrEmpty() &&
                    u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null,
            ) { "That isn't a server address. Type it as host:port, http://host:port or https://host:port." }
            val port = if (u.port > 0) u.port else if (u.scheme == "https") 443 else DEFAULT_PORT
            return "${u.scheme}://${u.host}:$port"
        }
    }
}
