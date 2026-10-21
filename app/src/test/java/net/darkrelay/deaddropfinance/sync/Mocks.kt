package net.darkrelay.deaddropfinance.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

/** A local HTTP server's answer: status and body, with an optional delay (for timeouts). */
class Answer(val status: Int, val body: String, val delayMs: Long = 0)

private fun HttpExchange.reply(a: Answer) {
    if (a.delayMs > 0) Thread.sleep(a.delayMs)
    val bytes = a.body.toByteArray()
    sendResponseHeaders(a.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
    close()
}

/**
 * A SimpleFIN Bridge stand-in on 127.0.0.1 (plain HTTP: the client allows that only for loopback in tests).
 * Login and data are invented at test time.
 */
class SimpleFinMock : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port get() = server.address.port
    val user = "u" + System.nanoTime()
    val pass = "p" + System.nanoTime()
    val accessUrl get() = "http://$user:$pass@127.0.0.1:$port/simplefin"
    var claimed = false
    var accountsAnswer: (query: String) -> Answer = { Answer(200, accountSet()) }
    val queries = mutableListOf<String>()

    /** A setup token that decodes to this server's claim address. */
    fun setupToken(): String = Base64.getEncoder().encodeToString("http://127.0.0.1:$port/claim/one".toByteArray())

    init {
        server.createContext("/claim/") { ex ->
            if (ex.requestMethod != "POST") return@createContext ex.reply(Answer(405, ""))
            if (claimed) return@createContext ex.reply(Answer(403, "already claimed"))
            claimed = true
            ex.reply(Answer(200, accessUrl))
        }
        server.createContext("/simplefin/accounts") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            val want = "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
            if (auth != want) return@createContext ex.reply(Answer(403, "forbidden"))
            val q = ex.requestURI.rawQuery.orEmpty()
            synchronized(queries) { queries += q }
            ex.reply(accountsAnswer(q))
        }
        server.start()
    }

    override fun close() = server.stop(0)

    companion object {
        fun txn(id: String, epoch: Long, amount: String, desc: String, pending: Boolean = false) =
            """{"id":"$id","posted":${if (pending) 0 else epoch},"transacted_at":$epoch,"amount":"$amount","description":"$desc","pending":$pending}"""

        fun accountSet(balance: String = "100.00", txns: List<String> = emptyList(), errlist: String = "[]", id: String = "ACT-1") =
            """{"errlist":$errlist,"connections":[{"conn_id":"CON-1","name":"Sample Bank","org_id":"o1","sfin_url":"https://example.invalid"}],
               "accounts":[{"id":"$id","name":"Everyday","conn_id":"CON-1","currency":"USD","balance":"$balance","balance-date":0,
               "transactions":[${txns.joinToString(",")}]}]}"""
    }
}

/**
 * Certificates made at test time with the JDK's keytool: a server certificate for 127.0.0.1 and a client one
 * (standing in for the user's Teller application certificate). Nothing is committed.
 */
class TestCerts private constructor(val dir: File) {
    lateinit var serverStore: KeyStore; private set
    lateinit var clientStore: KeyStore; private set
    lateinit var otherClientStore: KeyStore; private set
    val storePass = "test-only".toCharArray()

    val clientCertPem get() = pem("CERTIFICATE", clientStore.getCertificate("c").encoded)
    val clientKeyPem get() = pem("PRIVATE KEY", (clientStore.getKey("c", storePass) as PrivateKey).encoded)
    val otherCertPem get() = pem("CERTIFICATE", otherClientStore.getCertificate("c").encoded)
    val otherKeyPem get() = pem("PRIVATE KEY", (otherClientStore.getKey("c", storePass) as PrivateKey).encoded)

    /** Trust for the client side: only the test server's certificate. */
    fun clientTrust(): Array<TrustManager> {
        val ts = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("s", serverStore.getCertificate("s")) }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ts) }.trustManagers
    }

    val clientCertificate get() = clientStore.getCertificate("c") as X509Certificate

    /**
     * The server's TLS: its own certificate, and it asks for a client certificate without failing the handshake.
     * Like Teller, it answers 400 at the HTTP level when the right certificate isn't presented (TellerMock checks).
     */
    fun serverContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(serverStore, storePass) }
        val acceptAny = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(acceptAny), null) }
    }

    companion object {
        private var cached: TestCerts? = null

        fun get(): TestCerts = cached ?: make().also { cached = it }

        private fun pem(type: String, der: ByteArray) =
            "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

        private fun make(): TestCerts {
            val dir = Files.createTempDirectory("ddf-certs").toFile().apply { deleteOnExit() }
            val c = TestCerts(dir)
            fun gen(file: String, alias: String, dn: String, ext: String?) {
                val keytool = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
                val args = mutableListOf(
                    keytool.path, "-genkeypair", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-alias", alias, "-dname", dn,
                    "-keystore", File(dir, file).path, "-storetype", "PKCS12", "-storepass", String(c.storePass), "-keypass", String(c.storePass),
                )
                if (ext != null) args += listOf("-ext", ext)
                val p = ProcessBuilder(args).redirectErrorStream(true).start()
                p.inputStream.readAllBytes()
                check(p.waitFor() == 0) { "keytool failed" }
            }
            gen("server.p12", "s", "CN=127.0.0.1", "SAN=ip:127.0.0.1")
            gen("client.p12", "c", "CN=Test Client", null)
            gen("other.p12", "c", "CN=Other Client", null)
            fun load(f: String) = KeyStore.getInstance("PKCS12").apply { File(dir, f).inputStream().use { load(it, c.storePass) } }
            c.serverStore = load("server.p12")
            c.clientStore = load("client.p12")
            c.otherClientStore = load("other.p12")
            return c
        }
    }
}

/** A Teller API stand-in over mutual TLS: the request must present the test client certificate and the token. */
class TellerMock(private val certs: TestCerts, val accessToken: String) : AutoCloseable {
    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl get() = "https://127.0.0.1:${server.address.port}"
    var accountType = "depository"
    var balance = "250.00"
    var transactions: List<String> = emptyList()
    /** Overrides every answer (for errors), or null for the normal ones. */
    var forced: Answer? = null
    var hits = 0

    init {
        server.httpsConfigurator = object : HttpsConfigurator(certs.serverContext()) {
            override fun configure(params: HttpsParameters) {
                params.setSSLParameters(sslContext.defaultSSLParameters.apply { wantClientAuth = true })
            }
        }
        server.createContext("/") { ex ->
            synchronized(this) { hits++ }
            forced?.let { return@createContext ex.reply(it) }
            val peer = runCatching { (ex as com.sun.net.httpserver.HttpsExchange).sslSession.peerCertificates.firstOrNull() }.getOrNull()
            if (peer != certs.clientCertificate) return@createContext ex.reply(Answer(400, """{"error":{"code":"missing_certificate","message":"x"}}"""))
            val want = "Basic " + Base64.getEncoder().encodeToString("$accessToken:".toByteArray())
            if (ex.requestHeaders.getFirst("Authorization") != want) return@createContext ex.reply(Answer(403, """{"error":{"code":"forbidden","message":"x"}}"""))
            val path = ex.requestURI.path
            when {
                path == "/accounts" -> ex.reply(
                    Answer(200, """[{"id":"acc_test1","enrollment_id":"enr_test1","institution":{"id":"sample","name":"Sample Bank"},"name":"Checking","type":"$accountType",
                        "subtype":"checking","currency":"USD","last_four":"0001","status":"open","links":{"balances":"x","transactions":"y"}}]"""),
                )
                path == "/accounts/acc_test1/balances" -> ex.reply(Answer(200, """{"account_id":"acc_test1","ledger":"$balance","available":"$balance","links":{}}"""))
                path == "/accounts/acc_test1/transactions" -> ex.reply(Answer(200, "[" + transactions.joinToString(",") + "]"))
                else -> ex.reply(Answer(404, """{"error":{"code":"not_found","message":"x"}}"""))
            }
        }
        server.start()
    }

    override fun close() = server.stop(0)

    companion object {
        fun txn(id: String, date: String, amount: String, desc: String, status: String = "posted") =
            """{"id":"$id","account_id":"acc_test1","amount":"$amount","date":"$date","description":"$desc","status":"$status","type":"card_payment","details":{"processing_status":"complete"}}"""
    }
}
