package net.darkrelay.deaddropfinance.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64

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
