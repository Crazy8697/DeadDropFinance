package net.darkrelay.deaddropfinance.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/**
 * The user's own Teller application certificate and private key (Teller authenticates the app with mutual TLS).
 * Parsing and checking happen here; the PEM text itself is only ever kept in SecretStore.
 */
class TellerCredentials private constructor(private val key: PrivateKey, private val chain: List<X509Certificate>) {

    /** An SSL socket factory that presents this certificate. [trust] is for tests (a local server); null means the platform's. */
    fun sslSocketFactory(trust: Array<TrustManager>? = null): SSLSocketFactory {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("teller", key, CharArray(0), chain.toTypedArray())
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, trust, null) }.socketFactory
    }

    val expires get() = chain.first().notAfter

    class Invalid(message: String) : Exception(message)

    companion object {
        /** Checks the two PEM texts and returns usable credentials, or throws [Invalid] with a plain explanation. */
        fun parse(certPem: String, keyPem: String): TellerCredentials {
            val certs = try {
                CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(certPem.toByteArray())).filterIsInstance<X509Certificate>()
            } catch (e: Exception) {
                emptyList()
            }
            if (certs.isEmpty()) throw Invalid("That file doesn't contain a certificate. Choose the certificate file from your Teller dashboard (it starts with BEGIN CERTIFICATE).")
            val key = parseKey(keyPem)
            val now = java.util.Date()
            if (certs.first().notAfter.before(now)) throw Invalid("That certificate has expired. Issue a new one in your Teller dashboard and import it.")
            val alg = when (key.algorithm) {
                "RSA" -> "SHA256withRSA"
                "EC" -> "SHA256withECDSA"
                else -> throw Invalid("That private key type (${key.algorithm}) isn't supported.")
            }
            val probe = "ddf".toByteArray()
            val ok = try {
                val sig = Signature.getInstance(alg).apply { initSign(key); update(probe) }.sign()
                Signature.getInstance(alg).apply { initVerify(certs.first().publicKey); update(probe) }.verify(sig)
            } catch (e: Exception) {
                false
            }
            if (!ok) throw Invalid("That certificate and private key don't belong together. Choose the two files issued together.")
            return TellerCredentials(key, certs)
        }

        private fun blocks(pem: String): List<Pair<String, ByteArray>> =
            Regex("-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \\1-----", RegexOption.DOT_MATCHES_ALL).findAll(pem).mapNotNull { m ->
                try { m.groupValues[1] to Base64.getMimeDecoder().decode(m.groupValues[2].trim()) } catch (e: IllegalArgumentException) { null }
            }.toList()

        private fun parseKey(pem: String): PrivateKey {
            val bs = blocks(pem)
            if (bs.any { it.first == "ENCRYPTED PRIVATE KEY" }) throw Invalid("That private key is protected by a password. Teller's key files aren't; choose the key file straight from the dashboard.")
            val (type, der) = bs.firstOrNull { it.first in setOf("PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY") }
                ?: throw Invalid("That file doesn't contain a private key. Choose the private key file from your Teller dashboard (it starts with BEGIN PRIVATE KEY).")
            val pkcs8 = when (type) {
                "PRIVATE KEY" -> der
                "RSA PRIVATE KEY" -> Der.pkcs8Rsa(der)
                else -> Der.pkcs8Ec(der) ?: throw Invalid("That private key's format can't be read.")
            }
            for (alg in listOf("RSA", "EC")) {
                try { return KeyFactory.getInstance(alg).generatePrivate(PKCS8EncodedKeySpec(pkcs8)) } catch (e: Exception) { /* try the next */ }
            }
            throw Invalid("That private key's format can't be read.")
        }
    }

    /** Just enough DER to turn the two older private key layouts into PKCS#8. */
    private object Der {
        private fun len(n: Int): ByteArray = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }

        private fun tlv(tag: Int, body: ByteArray) = ByteArrayOutputStream().apply { write(tag); write(len(body.size)); write(body) }.toByteArray()

        private val RSA_ALG = tlv(0x30, byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00))
        private val EC_OID = byteArrayOf(0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)

        fun pkcs8Rsa(pkcs1: ByteArray) = tlv(0x30, byteArrayOf(0x02, 0x01, 0x00) + RSA_ALG + tlv(0x04, pkcs1))

        /** SEC1 ECPrivateKey: pulls out the curve [0] parameters and wraps the whole thing. Null if they're absent. */
        fun pkcs8Ec(sec1: ByteArray): ByteArray? {
            var i = 0
            fun readLen(): Int {
                val b = sec1[i++].toInt() and 0xFF
                if (b < 0x80) return b
                var n = 0
                repeat(b and 0x7F) { n = (n shl 8) or (sec1[i++].toInt() and 0xFF) }
                return n
            }
            if (sec1.isEmpty() || sec1[i++] != 0x30.toByte()) return null
            readLen()
            while (i < sec1.size) {
                val tag = sec1[i++].toInt() and 0xFF
                val l = readLen()
                if (tag == 0xA0) {
                    val oid = sec1.copyOfRange(i, i + l)
                    return tlv(0x30, byteArrayOf(0x02, 0x01, 0x00) + tlv(0x30, EC_OID + oid) + tlv(0x04, sec1))
                }
                i += l
            }
            return null
        }
    }
}
