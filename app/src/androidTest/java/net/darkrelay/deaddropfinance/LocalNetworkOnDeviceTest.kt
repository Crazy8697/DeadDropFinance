package net.darkrelay.deaddropfinance

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.darkrelay.deaddropfinance.serversync.HttpTransport
import net.darkrelay.deaddropfinance.serversync.LocalNetwork
import net.darkrelay.deaddropfinance.serversync.LocalNetworkTransport
import net.darkrelay.deaddropfinance.serversync.ServerClient
import net.darkrelay.deaddropfinance.serversync.ServerException
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The server reach fix (1.6.0) on a real Android 17 device or emulator, run by hand against a throwaway test server
 * (never the live one) at an address on the device's own Wi-Fi network: instrumentation argument ddfLanUrl. On a fresh
 * install the local network permission is off, and the connection is blocked and reported as that; once granted, the
 * same address answers with the server's key. Skipped without ddfLanUrl or below Android 17.
 */
@RunWith(AndroidJUnit4::class)
class LocalNetworkOnDeviceTest {

    @Test fun aServerOnTheWifiNetworkNeedsTheLocalNetworkPermission() {
        val url = InstrumentationRegistry.getArguments().getString("ddfLanUrl")
        assumeTrue("set ddfLanUrl to a test server on the device's Wi-Fi network", !url.isNullOrBlank())
        assumeTrue(LocalNetwork.needed())
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        assumeTrue("start from a fresh install (permission not granted)", ctx.checkSelfPermission(LocalNetwork.PERMISSION) != PackageManager.PERMISSION_GRANTED)

        fun transport() = LocalNetworkTransport(HttpTransport(url!!, connectTimeoutMs = 4_000, readTimeoutMs = 4_000)) { LocalNetwork.allowed(ctx) }

        // 1.5.0's case: blocked by Android, now reported as what it is
        val blocked = runCatching { ServerClient.serverKey(transport()) }.exceptionOrNull()
        assertEquals(ServerException.Reach.LOCAL_NETWORK_BLOCKED, (blocked as ServerException).reach)

        // granted (as the Allow button does), the same address answers
        ins.uiAutomation.grantRuntimePermission(ctx.packageName, LocalNetwork.PERMISSION)
        var key: ByteArray? = null
        val until = System.currentTimeMillis() + 15_000
        while (key == null && System.currentTimeMillis() < until) {
            key = runCatching { ServerClient.serverKey(transport()).publicKey }.getOrNull()
            if (key == null) Thread.sleep(500)
        }
        assertEquals(32, key?.size)
    }
}
