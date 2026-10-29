package net.darkrelay.deaddropfinance.serversync

/**
 * What the desktop program's Add a phone QR code holds (1.5.0, desktop 0.10.0): the server's address, its key
 * fingerprint and a one-time pairing code. Never the password and never a key: the fingerprint only lets the phone check
 * the key it gets from that address, and the code works once, for 10 minutes.
 *
 *   deaddrop-pair:1?server=<address, URL-encoded>&fp=<16 hex>&code=<XXXX-XXXX>
 */
object PairingQr {
    data class Info(val serverUrl: String, val fingerprint: String, val code: String)

    private const val PREFIX = "deaddrop-pair:1?"
    private val FINGERPRINT = Regex("^[0-9a-f]{16}$")
    /** The server's code alphabet (no 0, 1, I, L, O or U). */
    private val CODE = Regex("^[A-HJKMNP-TV-Z2-9]{4}-[A-HJKMNP-TV-Z2-9]{4}$")

    fun encode(serverUrl: String, fingerprint: String, code: String): String {
        val fp = normal(fingerprint)
        require(FINGERPRINT.matches(fp)) { "not a key fingerprint" }
        require(CODE.matches(code)) { "not a pairing code" }
        return PREFIX + "server=" + enc(HttpTransport.normalize(serverUrl)) + "&fp=" + fp + "&code=" + code
    }

    /** The QR code's content, or null when it isn't a Dead Drop pairing code (or is damaged). */
    fun parse(text: String): Info? {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return null
        val parts = t.removePrefix(PREFIX).split('&').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i) to dec(p.substring(i + 1))
        }.toMap()
        val server = parts["server"]?.let { runCatching { HttpTransport.normalize(it) }.getOrNull() } ?: return null
        val fp = parts["fp"]?.let(::normal)?.takeIf { FINGERPRINT.matches(it) } ?: return null
        val code = parts["code"]?.trim()?.uppercase()?.takeIf { CODE.matches(it) } ?: return null
        return Info(server, fp, code)
    }

    fun sameFingerprint(a: String, b: String) = normal(a) == normal(b)

    private fun normal(fp: String) = fp.lowercase().filter { it.isLetterOrDigit() }

    // percent-encoding of the address (kept here, so nothing in the shared code but HttpTransport touches java.net)
    private const val SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private fun enc(v: String) = v.toByteArray(Charsets.UTF_8).joinToString("") { b ->
        val c = (b.toInt() and 0xff).toChar()
        if (c in SAFE) c.toString() else "%%%02X".format(b.toInt() and 0xff)
    }

    private fun dec(v: String): String? = runCatching {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < v.length) {
            if (v[i] == '%') { out.write(v.substring(i + 1, i + 3).toInt(16)); i += 3 } else { out.write(v[i].code); i++ }
        }
        out.toString(Charsets.UTF_8)
    }.getOrNull()
}
