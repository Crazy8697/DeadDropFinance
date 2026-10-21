package net.darkrelay.deaddropfinance

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import net.darkrelay.deaddropfinance.UpdateCheck.Result
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The HTTP side against a local mock server (the JDK's built-in HttpServer, so no extra dependency). Synthetic
 * releases only, versioned 99.x so they never go stale. Nothing leaves this machine.
 */
class UpdateClientTest {

    private lateinit var server: HttpServer
    @Volatile private var status = 200
    @Volatile private var body = ""
    @Volatile private var delayMs = 0L
    @Volatile private var headers: Map<String, List<String>> = emptyMap()
    private val url get() = "http://127.0.0.1:${server.address.port}/repos/x/y/releases/latest"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            headers = ex.requestHeaders.mapKeys { it.key.lowercase() }
            if (delayMs > 0) Thread.sleep(delayMs)
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong().coerceAtLeast(1))
            ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) else it.write(' '.code) }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun release(
        tag: String = "v99.0.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<String> = listOf("DeadDropFinance.apk", "DeadDropFinance.apk.sha256"),
    ) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://example.com/releases/tag/$tag",
         "assets":[${assets.joinToString(",") { """{"name":"$it"}""" }}]}
    """.trimIndent()

    private fun check(installed: String = "0.5.0") = UpdateClient.check(url, installed, 2000, 1000)

    @Test fun newerReleaseIsOffered() {
        body = release()
        val r = check()
        assertTrue(r is Result.Available)
        assertEquals("99.0.0", (r as Result.Available).release.version)
        assertEquals("https://example.com/releases/tag/v99.0.0", r.release.notesUrl)
    }

    @Test fun sameVersionIsNotOffered() {
        body = release(tag = "v99.0.0")
        assertEquals(Result.UpToDate, check("99.0.0"))
    }

    @Test fun olderVersionIsNotOffered() {
        body = release(tag = "v99.0.0")
        assertEquals(Result.UpToDate, check("99.1.0"))
    }

    @Test fun draftAndPrereleaseAreIgnored() {
        body = release(draft = true)
        assertEquals(Result.UpToDate, check())
        body = release(prerelease = true)
        assertEquals(Result.UpToDate, check())
    }

    @Test fun missingEitherAssetIsIgnored() {
        body = release(assets = listOf("DeadDropFinance.apk"))
        assertEquals(Result.UpToDate, check())
        body = release(assets = listOf("DeadDropFinance.apk.sha256"))
        assertEquals(Result.UpToDate, check())
        body = release(assets = listOf("Other.apk", "Other.apk.sha256"))
        assertEquals(Result.UpToDate, check())
    }

    @Test fun skippedVersionStaysQuietAtStartupButNotOnManualCheck() {
        body = release(tag = "v99.2.0")
        val r = check() as Result.Available
        assertEquals(false, UpdateCheck.shouldOffer(r.release, "99.2.0", manual = false))
        assertEquals(true, UpdateCheck.shouldOffer(r.release, "99.2.0", manual = true))
    }

    @Test fun tenIsNewerThanNine() {
        body = release(tag = "v99.10.0")
        assertTrue(check("99.9.0") is Result.Available)
        body = release(tag = "v99.9.0")
        assertEquals(Result.UpToDate, check("99.10.0"))
    }

    @Test fun timeoutFailsWithoutThrowing() {
        body = release()
        delayMs = 2500           // longer than the 1 s read timeout
        assertEquals(Result.Failed, check())
    }

    @Test fun badJsonFailsWithoutThrowing() {
        body = "{not json"
        assertEquals(Result.Failed, check())
    }

    @Test fun rateLimitOrErrorFails() {
        body = """{"message":"API rate limit exceeded"}"""
        status = 403
        assertEquals(Result.Failed, check())
        status = 500
        assertEquals(Result.Failed, check())
    }

    @Test fun noServerFails() {
        val dead = "http://127.0.0.1:1/none"
        assertEquals(Result.Failed, UpdateClient.check(dead, "0.5.0", 500, 500))
    }

    @Test fun onlyAnAnonymousRequestIsSent() {
        body = release()
        check("0.5.0")
        assertEquals(listOf("DeadDropFinance/0.5.0 (+https://darkrelay.net)"), headers["user-agent"])
        assertEquals(listOf("application/vnd.github+json"), headers["accept"])
        assertTrue(headers.keys.none { it == "authorization" || it == "cookie" })
    }
}
