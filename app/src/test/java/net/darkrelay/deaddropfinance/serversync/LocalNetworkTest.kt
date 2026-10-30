package net.darkrelay.deaddropfinance.serversync

import android.app.Application
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import net.darkrelay.deaddropfinance.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * Server reach (1.6.0). The cause of "The server can't be reached" on Android 17: the app targets Android 17, which
 * blocks a server on the phone's own Wi-Fi network unless the app holds ACCESS_LOCAL_NETWORK, and 1.5.0 didn't declare
 * or ask for it. These check the manifest declares it, the network security config keeps plain HTTP for the server
 * but not for the update check's hosts, and a failure without the permission is reported as that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocalNetworkTest {

    private val merged: File by lazy {
        val config = "com/android/tools/test_config.properties"
        val stream = listOfNotNull(Thread.currentThread().contextClassLoader, javaClass.classLoader, ClassLoader.getSystemClassLoader())
            .firstNotNullOfOrNull { it.getResourceAsStream(config) }
        val variant = BuildConfig.BUILD_TYPE
        val path = stream?.use { s -> Properties().apply { load(s) }.getProperty("android_merged_manifest") }
            ?: "build/intermediates/packaged_manifests/${variant}UnitTest/process${variant.replaceFirstChar { it.uppercase() }}UnitTestManifest/AndroidManifest.xml"
        listOf(File(path), File("app", path)).first { it.isFile }
    }

    private fun xml(f: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)

    private val android = "http://schemas.android.com/apk/res/android"

    @Test fun theManifestDeclaresTheLocalNetworkPermissionAndTheConfig() {
        val doc = xml(merged)
        val perms = doc.getElementsByTagName("uses-permission").let { l -> (0 until l.length).map { (l.item(it) as Element).getAttributeNS(android, "name") } }
        assertTrue(perms.toString(), LocalNetwork.PERMISSION in perms)
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", LocalNetwork.PERMISSION)
        val app = doc.getElementsByTagName("application").item(0) as Element
        assertTrue(app.getAttributeNS(android, "networkSecurityConfig").isNotEmpty())
        // the config decides cleartext now, not a blanket switch
        assertFalse(app.hasAttributeNS(android, "usesCleartextTraffic"))
    }

    @Test fun plainHttpStaysForTheServerButNotForTheUpdateCheck() {
        val f = listOf(File("src/main/res/xml/network_security_config.xml"), File("app/src/main/res/xml/network_security_config.xml")).first { it.isFile }
        val doc = xml(f)
        val base = doc.getElementsByTagName("base-config").item(0) as Element
        assertEquals("true", base.getAttribute("cleartextTrafficPermitted"))
        val dc = doc.getElementsByTagName("domain-config").item(0) as Element
        assertEquals("false", dc.getAttribute("cleartextTrafficPermitted"))
        val domains = dc.getElementsByTagName("domain").let { l -> (0 until l.length).map { (l.item(it) as Element).textContent.trim() } }.toSet()
        assertEquals(setOf("github.com", "githubusercontent.com"), domains)
        // the update check, its download and its checksum are github.com URLs, all https
        for (u in listOf(net.darkrelay.deaddropfinance.UpdateCheck.REAL_UPDATE_URL, net.darkrelay.deaddropfinance.UpdateCheck.DOWNLOAD_URL,
            net.darkrelay.deaddropfinance.UpdateCheck.SHA256_DOWNLOAD_URL)) {
            assertTrue(u, u.startsWith("https://") && java.net.URI(u).host.let { it == "github.com" || it.endsWith(".github.com") })
        }
    }

    @Test fun onlyAndroid17AndUpNeedThePermission() {
        assertFalse(LocalNetwork.needed(33))
        assertFalse(LocalNetwork.needed(36))
        assertTrue(LocalNetwork.needed(37))
    }

    private class Failing(val e: IOException) : Transport {
        override fun get(path: String): Transport.Response = throw e
        override fun post(path: String, body: ByteArray): Transport.Response = throw e
    }

    @Test fun aFailureWithThePermissionOffSaysSo() {
        val timeout = SocketTimeoutException("failed to connect to /192.0.2.10 (port 5090) after 8000ms")
        val off = LocalNetworkTransport(Failing(timeout)) { false }
        try {
            off.get("/v1/server-key"); fail()
        } catch (e: LocalNetworkBlocked) {
            assertSame(timeout, e.cause)
        }
        val x = runCatching { ServerClient.serverKey(off) }.exceptionOrNull() as ServerException
        assertEquals(ServerException.Reach.LOCAL_NETWORK_BLOCKED, x.reach)
        assertEquals(ServerClient.LOCAL_NETWORK_BLOCKED, x.message)
    }

    @Test fun withThePermissionOnTheRealFailureShows() {
        val on = LocalNetworkTransport(Failing(SocketTimeoutException("connect timed out"))) { true }
        val x = runCatching { ServerClient.serverKey(on) }.exceptionOrNull() as ServerException
        assertEquals(ServerException.Reach.TIMEOUT, x.reach)
        val ok = LocalNetworkTransport(object : Transport {
            override fun get(path: String) = Transport.Response(200, "{}".toByteArray())
            override fun post(path: String, body: ByteArray) = Transport.Response(200, body)
        }) { false }
        // permission off, but the server answered (a tailnet or https:// server): nothing is refused up front
        assertEquals(200, ok.get("/health").status)
    }
}
