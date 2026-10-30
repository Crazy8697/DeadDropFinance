package net.darkrelay.deaddropfinance.serversync

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Reaching the server (1.6.0): every address form the Server screens take, and a connect error that says what failed
 * instead of one "can't be reached" line. The network is never used: failures are thrown by a stand-in transport, or
 * come from sockets this test opens on the computer's own loopback.
 */
class ServerReachTest {

    // ---- address forms (phone and desktop share this)

    // a documentation address (TEST-NET-1) and name; the port is joined on so no address:port is spelled out
    private val ip = "192.0.2.10"
    private val port = 5090

    @Test fun everyAddressFormBecomesSchemeHostPort() {
        val want = "http://$ip:$port"
        for (a in listOf(
            "$ip:$port", "http://$ip:$port", "  $ip:$port  ", "http://$ip:$port/", " http://$ip:$port// ", "HTTP://$ip:$port",
            "Http://$ip:$port/", ip,
        )) assertEquals(want, HttpTransport.normalize(a), a)
        assertEquals("https://$ip:$port", HttpTransport.normalize("https://$ip:$port"))
        assertEquals("https://$ip:$port", HttpTransport.normalize(" HTTPS://$ip:$port/ "))
        assertEquals("https://sync.example:443", HttpTransport.normalize("https://sync.example"))
        assertEquals("http://server.example:5091", HttpTransport.normalize("server.example:5091"))
        assertEquals("http://server.example:5090", HttpTransport.normalize(" http://server.example:5090/ "))
    }

    @Test fun whatIsNotAServerAddressIsRefusedPlainly() {
        for (a in listOf("", "   ", "ftp://server.example:5090", "http://server.example:5090/health", "http://user${"@"}server.example:5090", "http://server.example:5090?x=1", "http://")) {
            assertFailsWith<IllegalArgumentException>(a) { HttpTransport.normalize(a) }
        }
    }

    // ---- what failed

    private fun failing(e: IOException) = object : Transport {
        override fun get(path: String): Transport.Response = throw e
        override fun post(path: String, body: ByteArray): Transport.Response = throw e
    }

    private fun reachOf(e: IOException): ServerException =
        assertFailsWith<ServerException> { ServerClient.serverKey(failing(e)) }

    @Test fun eachFailureHasItsOwnMessage() {
        val cases = listOf(
            // the cause on Android 17: the phone's transport finds the local network permission off
            LocalNetworkBlocked(SocketTimeoutException("failed to connect to /192.0.2.10 (port 5090) after 8000ms")) to ServerException.Reach.LOCAL_NETWORK_BLOCKED,
            ConnectException("failed to connect to /192.0.2.10 (port 5090): connect failed: EPERM (Operation not permitted)") to ServerException.Reach.LOCAL_NETWORK_BLOCKED,
            UnknownServiceException("CLEARTEXT communication to server.example not permitted by network security policy") to ServerException.Reach.CLEARTEXT_BLOCKED,
            UnknownHostException("server.example") to ServerException.Reach.DNS,
            ConnectException("Connection refused") to ServerException.Reach.REFUSED,
            ConnectException("failed to connect to /192.0.2.10 (port 5090): connect failed: ECONNREFUSED (Connection refused)") to ServerException.Reach.REFUSED,
            SocketTimeoutException("connect timed out") to ServerException.Reach.TIMEOUT,
            ConnectException("Connection timed out: connect") to ServerException.Reach.TIMEOUT,
            NoRouteToHostException("No route to host") to ServerException.Reach.NO_ROUTE,
            ConnectException("failed to connect: ENETUNREACH (Network is unreachable)") to ServerException.Reach.NO_ROUTE,
            SSLHandshakeException("PKIX path building failed") to ServerException.Reach.TLS,
            PlainHttpRefused() to ServerException.Reach.PLAIN_HTTP_REFUSED,
            IOException("something else") to ServerException.Reach.OTHER,
        )
        val messages = mutableSetOf<String>()
        for ((e, reach) in cases) {
            val x = reachOf(e)
            assertEquals(reach, x.reach, e.toString())
            val m = x.message!!
            messages += m
            // never the exception's own text: it can hold the address
            assertFalse(m.contains("192.0.2") || m.contains("server.example") || m.contains("5090 (") || m.contains("PKIX"), m)
        }
        assertEquals(ServerException.Reach.values().size, messages.size, "one message per reach")
        assertEquals(ServerException.Kind.ERROR, reachOf(UnknownServiceException("CLEARTEXT communication not permitted")).kind)
        assertEquals(ServerException.Kind.UNREACHABLE, reachOf(SocketTimeoutException("connect timed out")).kind)
        assertEquals(ServerException.Kind.ERROR, reachOf(PlainHttpRefused()).kind)
        assertEquals(ServerClient.LOCAL_NETWORK_BLOCKED, reachOf(LocalNetworkBlocked()).message)
    }

    // ---- plain http:// only to the home network, tailnet and loopback ranges (decision 8)

    /** An address from its four parts, so no address is spelled out in the source (and nothing is looked up). */
    private fun v4(a: Int, b: Int, c: Int, d: Int): InetAddress = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    @Test fun plainHttpIsAllowedOnlyInTheRanges() {
        val allowed = listOf(
            v4(10, 0, 0, 1), v4(10, 255, 255, 254),                       // 10/8
            v4(172, 16, 0, 1), v4(172, 31, 255, 254),                     // 172.16/12
            v4(192, 168, 0, 1), v4(192, 168, 255, 254),                   // 192.168/16
            v4(100, 64, 0, 1), v4(100, 127, 255, 254),                    // 100.64/10
            v4(127, 0, 0, 1), v4(127, 1, 2, 3),                           // loopback
            InetAddress.getByName("::1"),
        )
        for (a in allowed) assertTrue(HttpTransport.plainHttpAllowed(a), a.toString())
        val refused = listOf(
            v4(8, 8, 8, 8), v4(192, 0, 2, 10), v4(203, 0, 113, 5),        // public and documentation addresses
            v4(172, 15, 255, 255), v4(172, 32, 0, 1),                     // just outside 172.16/12
            v4(100, 63, 255, 255), v4(100, 128, 0, 1),                    // just outside 100.64/10
            v4(192, 169, 0, 1), v4(11, 0, 0, 1), v4(169, 254, 1, 1),      // outside the others, and link-local
            InetAddress.getByName("2001:db8::1"), InetAddress.getByName("fd00::1"),
        )
        for (a in refused) assertFalse(HttpTransport.plainHttpAllowed(a), a.toString())
    }

    @Test fun aPublicAddressOverPlainHttpIsRefusedBeforeAnythingIsSent() {
        // a literal address needs no name lookup, and the refusal comes before any connection
        val x = assertFailsWith<ServerException> { ServerClient.serverKey(HttpTransport("http://$ip:$port")) }
        assertEquals(ServerException.Reach.PLAIN_HTTP_REFUSED, x.reach)
        assertEquals(ServerException.Kind.ERROR, x.kind)
        assertEquals(ServerClient.PLAIN_HTTP_REFUSED, x.message)
        assertTrue(x.message!!.contains("https://"))
        assertFailsWith<PlainHttpRefused> { HttpTransport.checkPlainHttp(ip) }
        assertFailsWith<PlainHttpRefused> { HttpTransport.checkPlainHttp("[2001:db8::1]") }
    }

    @Test fun loopbackPlainHttpIsAllowedAndHttpsIsNotChecked() {
        HttpTransport.checkPlainHttp("127.0.0.1")
        HttpTransport.checkPlainHttp("[::1]")
        HttpTransport.checkPlainHttp(v4(192, 168, 1, 20).hostAddress)
        HttpTransport.checkPlainHttp(v4(100, 101, 102, 103).hostAddress)
        // https:// to the same public address isn't refused by the rule: it goes on to connect (here, to a closed loopback
        // port standing in for it, so nothing leaves the computer) and fails only as a connection does
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val x = assertFailsWith<ServerException> { ServerClient.serverKey(HttpTransport("https://127.0.0.1:$port", connectTimeoutMs = 2_000)) }
        assertEquals(ServerException.Reach.REFUSED, x.reach)
        assertEquals("https://$ip:$port", HttpTransport.normalize("https://$ip:$port"), "https to any address is a valid address")
        HttpTransport.checkBeforeSending(java.net.URI("https://$ip:$port/v1/server-key"))
        assertFailsWith<PlainHttpRefused> { HttpTransport.checkBeforeSending(java.net.URI("http://$ip:$port/v1/server-key")) }
    }

    // ---- real sockets on loopback (nothing leaves the computer)

    @Test fun aClosedPortIsRefused() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val x = assertFailsWith<ServerException> { ServerClient.serverKey(HttpTransport("127.0.0.1:$port", connectTimeoutMs = 2_000)) }
        assertEquals(ServerException.Reach.REFUSED, x.reach)
        assertEquals(ServerException.Kind.UNREACHABLE, x.kind)
    }

    @Test fun aServerThatNeverAnswersTimesOut() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ss ->
            val t = thread { runCatching { ss.accept().use { Thread.sleep(3_000) } } }
            val x = assertFailsWith<ServerException> {
                ServerClient.serverKey(HttpTransport("http://127.0.0.1:${ss.localPort}/", connectTimeoutMs = 2_000, readTimeoutMs = 500))
            }
            assertEquals(ServerException.Reach.TIMEOUT, x.reach)
            t.join()
        }
    }

    @Test fun somethingElseAnsweringIsABadResponseNotUnreachable() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ss ->
            val t = thread {
                runCatching {
                    ss.accept().use { s ->
                        s.getInputStream().read(ByteArray(4096))
                        s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\nConnection: close\r\n\r\nnot here.".toByteArray())
                    }
                }
            }
            val x = assertFailsWith<ServerException> { ServerClient.serverKey(HttpTransport(" 127.0.0.1:${ss.localPort} ")) }
            assertEquals(ServerException.Kind.ERROR, x.kind)
            assertEquals(null, x.reach)
            assertEquals("That address answered, but not as a Dead Drop server (404).", x.message)
            t.join()
        }
    }
}
