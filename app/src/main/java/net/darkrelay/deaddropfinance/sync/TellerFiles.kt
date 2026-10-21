package net.darkrelay.deaddropfinance.sync

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reads what the user picked for Teller: the teller.zip that Teller's signup downloads (both files at once), or one
 * PEM file at a time. Everything stays in memory; nothing is unpacked to storage.
 */
object TellerFiles {
    data class Found(val certificate: String? = null, val privateKey: String? = null)

    fun read(bytes: ByteArray): Found {
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return readZip(bytes)
        return classify(String(bytes, Charsets.UTF_8))
    }

    private fun classify(text: String): Found {
        val cert = if (text.contains("-----BEGIN CERTIFICATE-----")) text else null
        val key = if (Regex("-----BEGIN (RSA |EC |ENCRYPTED )?PRIVATE KEY-----").containsMatchIn(text)) text else null
        return Found(cert, key)
    }

    private fun readZip(bytes: ByteArray): Found {
        var found = Found()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val data = zip.readNBytes(MAX_ENTRY + 1)
                total += data.size
                if (data.size > MAX_ENTRY || total > MAX_TOTAL) break
                val f = classify(String(data, Charsets.UTF_8))
                found = Found(found.certificate ?: f.certificate, found.privateKey ?: f.privateKey)
            }
        }
        return found
    }

    private const val MAX_ENTRY = 64 * 1024
    private const val MAX_TOTAL = 256 * 1024
}
