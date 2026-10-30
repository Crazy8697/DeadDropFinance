package net.darkrelay.deaddropfinance.serversync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException

/**
 * Android 17's local network protection (1.6.0): an app that targets Android 17 can't reach an address on the phone's
 * own Wi-Fi network (a home server) without the ACCESS_LOCAL_NETWORK permission
 * (Nearby devices). Without it the connection just times out, so the app showed "The server can't be reached" while
 * the phone's browser reached the same address. Older Android versions don't have the permission and don't block.
 */
object LocalNetwork {
    /** The permission's name (Manifest.permission.ACCESS_LOCAL_NETWORK, API 37). */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Android 17 (API 37) and up enforce it. */
    const val FIRST_SDK = 37

    fun needed(sdk: Int = Build.VERSION.SDK_INT) = sdk >= FIRST_SDK

    fun allowed(context: Context, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        !needed(sdk) || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}

/**
 * The phone's transport to the server: plain HttpTransport, except that a failure while the local network permission
 * is off is reported as LocalNetworkBlocked, so the Server screen says what really failed. A server reached another
 * way (a tailnet through its VPN, or https:// on the internet) needs no permission, so nothing is refused up front.
 */
class LocalNetworkTransport(private val inner: Transport, private val allowed: () -> Boolean) : Transport {
    override fun get(path: String): Transport.Response = guard { inner.get(path) }

    override fun post(path: String, body: ByteArray): Transport.Response = guard { inner.post(path, body) }

    private inline fun guard(block: () -> Transport.Response): Transport.Response = try {
        block()
    } catch (e: IOException) {
        if (!allowed()) throw LocalNetworkBlocked(e)
        throw e
    }
}
