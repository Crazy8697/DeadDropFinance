package net.darkrelay.deaddropfinance

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import net.darkrelay.deaddropfinance.UpdateDownloader.Result
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UpdateDownloader against a local mock server (the same technique as UpdateClientTest): download, SHA-256 verify,
 * a mismatch or a failure always deletes anything left behind, and progress is reported as bytes arrive. Synthetic
 * release bytes only, nothing leaves this machine.
 */
class UpdateDownloaderTest {

    private lateinit var server: HttpServer
    @Volatile private var apkBytes: ByteArray = ByteArray(0)
    @Volatile private var shaBody: String = ""
    @Volatile private var apkStatus = 200
    @Volatile private var shaStatus = 200
    @Volatile private var apkDelayMs = 0L
    private val dir = Files.createTempDirectory("ddf-update-dl").toFile()

    private fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/apk") { ex ->
            if (apkDelayMs > 0) Thread.sleep(apkDelayMs)
            if (ex.requestMethod == "HEAD") {
                // a HEAD answer carries the length and no body
                ex.responseHeaders.add("Content-Length", apkBytes.size.toString())
                ex.sendResponseHeaders(apkStatus, -1)
                ex.close()
                return@createContext
            }
            ex.sendResponseHeaders(apkStatus, apkBytes.size.toLong().coerceAtLeast(1))
            ex.responseBody.use { if (apkBytes.isNotEmpty()) it.write(apkBytes) else it.write(' '.code) }
        }
        server.createContext("/sha") { ex ->
            val bytes = shaBody.toByteArray()
            ex.sendResponseHeaders(shaStatus, bytes.size.toLong().coerceAtLeast(1))
            ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) else it.write(' '.code) }
        }
        server.start()
        apkBytes = "sample release bytes".repeat(500).toByteArray()
        shaBody = "${hex(apkBytes)}  DeadDropFinance.apk\n"
        apkStatus = 200; shaStatus = 200; apkDelayMs = 0
    }

    @After fun stop() { server.stop(0); dir.deleteRecursively() }

    private val apkUrl get() = "http://127.0.0.1:${server.address.port}/apk"
    private val shaUrl get() = "http://127.0.0.1:${server.address.port}/sha"

    private fun download(dest: java.io.File, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        UpdateDownloader.download(apkUrl, shaUrl, dest, "DeadDropFinance/0.0.0-test", 2000, 2000, onProgress)

    @Test fun verifiedDownloadSucceedsAndMatchesTheHash() {
        val dest = java.io.File(dir, "update.apk")
        val r = download(dest)
        assertTrue(r is Result.Ok)
        assertTrue(dest.isFile)
        assertEquals(hex(apkBytes), hex(dest.readBytes()))
        assertFalse("no leftover temp file", java.io.File(dir, "update.apk.part").exists())
    }

    @Test fun progressIsReportedAsBytesArrive() {
        val dest = java.io.File(dir, "update.apk")
        val seen = mutableListOf<Pair<Long, Long>>()
        download(dest) { bytes, total -> seen += bytes to total }
        assertTrue(seen.isNotEmpty())
        assertEquals(0L, seen.first().first)
        assertEquals(apkBytes.size.toLong(), seen.last().first)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test fun aMismatchedChecksumDeletesTheFileAndFails() {
        shaBody = "${"0".repeat(64)}  DeadDropFinance.apk\n"
        val dest = java.io.File(dir, "update.apk")
        val r = download(dest)
        assertTrue(r is Result.Failed)
        assertFalse(dest.exists())
        assertFalse(java.io.File(dir, "update.apk.part").exists())
    }

    @Test fun aBadChecksumBodyFailsCleanly() {
        shaBody = "not a hash"
        val dest = java.io.File(dir, "update.apk")
        val r = download(dest)
        assertTrue(r is Result.Failed)
        assertFalse(dest.exists())
    }

    @Test fun aServerErrorOnTheApkFailsAndCleansUp() {
        apkStatus = 500
        val dest = java.io.File(dir, "update.apk")
        val r = download(dest)
        assertTrue(r is Result.Failed)
        assertFalse(dest.exists())
        assertFalse(java.io.File(dir, "update.apk.part").exists())
    }

    @Test fun aServerErrorOnTheChecksumFailsWithoutFetchingTheApk() {
        shaStatus = 404
        val dest = java.io.File(dir, "update.apk")
        val r = download(dest)
        assertTrue(r is Result.Failed)
        assertFalse(dest.exists())
    }

    @Test fun aTimeoutFailsWithoutThrowingAndCleansUp() {
        apkDelayMs = 2500
        val dest = java.io.File(dir, "update.apk")
        val r = download(dest)
        assertTrue(r is Result.Failed)
        assertFalse(dest.exists())
        assertFalse(java.io.File(dir, "update.apk.part").exists())
    }

    @Test fun noServerFailsWithoutThrowing() {
        val dest = java.io.File(dir, "update.apk")
        val r = UpdateDownloader.download("http://127.0.0.1:1/none", "http://127.0.0.1:1/none2", dest, "DeadDropFinance/0.0.0-test", 500, 500)
        assertTrue(r is Result.Failed)
        assertFalse(dest.exists())
    }

    @Test fun anExistingDestinationIsReplacedByAFreshVerifiedDownload() {
        val dest = java.io.File(dir, "update.apk")
        dest.writeText("stale leftover")
        val r = download(dest)
        assertTrue(r is Result.Ok)
        assertEquals(hex(apkBytes), hex(dest.readBytes()))
    }

    @Test fun sizeReportsTheServersContentLengthAndNullOnFailure() {
        assertEquals(apkBytes.size.toLong(), UpdateDownloader.size(apkUrl, "DeadDropFinance/0.0.0-test", 2000, 2000))
        apkStatus = 500
        assertEquals(null, UpdateDownloader.size(apkUrl, "DeadDropFinance/0.0.0-test", 2000, 2000))
        assertEquals(null, UpdateDownloader.size("http://127.0.0.1:1/none", "DeadDropFinance/0.0.0-test", 500, 500))
    }
}
