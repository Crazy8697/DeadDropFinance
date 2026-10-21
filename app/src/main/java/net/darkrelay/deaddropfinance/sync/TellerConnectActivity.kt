package net.darkrelay.deaddropfinance.sync

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.teller.connect.sdk.Config
import io.teller.connect.sdk.ConnectFragment
import io.teller.connect.sdk.ConnectListener
import io.teller.connect.sdk.Environment
import io.teller.connect.sdk.Error
import io.teller.connect.sdk.Payee
import io.teller.connect.sdk.Payment
import io.teller.connect.sdk.Product
import io.teller.connect.sdk.Registration
import io.teller.connect.sdk.SelectAccount
import net.darkrelay.deaddropfinance.Browsers
import net.darkrelay.deaddropfinance.DdfApp

/**
 * Hosts Teller Connect (Teller's official Android SDK, a WebView). The bank login happens inside Teller's page;
 * the app only gets the access token back. Around the SDK's WebView: top-level navigation stays on Teller's own
 * domains (anything else opens in the real browser), nothing from the page is logged, and the WebView's cookies
 * and storage are cleared when it closes. The token goes straight to [TellerHandoff] in memory, never into an
 * Intent.
 */
class TellerConnectActivity : FragmentActivity(), ConnectListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((application as DdfApp).lock.settings.hideScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val container = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(container)
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) { guard(v) }
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) { f.view?.let(::guard) }
        }, false)
        if (savedInstanceState != null) return
        val env = when (intent.getStringExtra(EXTRA_ENV)) {
            "sandbox" -> Environment.SANDBOX
            "production" -> Environment.PRODUCTION
            else -> Environment.DEVELOPMENT
        }
        val config = Config(
            appId = intent.getStringExtra(EXTRA_APP_ID).orEmpty(),
            environment = env,
            selectAccount = SelectAccount.MULTIPLE,
            products = listOf(Product.TRANSACTIONS, Product.BALANCE),
            enrollmentId = intent.getStringExtra(EXTRA_ENROLLMENT),
        )
        val fragment = ConnectFragment().apply { arguments = ConnectFragment.buildArgs(config) }
        supportFragmentManager.beginTransaction().add(container.id, fragment).commitNow()
    }

    private fun guard(root: View) {
        val web = findWebView(root) ?: return
        val client = web.webViewClient
        if (client !is GuardClient) web.webViewClient = GuardClient(client)
        val chrome = web.webChromeClient
        if (chrome !is GuardChrome) web.webChromeClient = GuardChrome(chrome)
    }

    private fun findWebView(v: View): WebView? {
        if (v is WebView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Teller's pages stay in the WebView; anything else opens in the user's browser. */
    private inner class GuardClient(private val inner: WebViewClient) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame || isTeller(request.url)) return inner.shouldOverrideUrlLoading(view, request)
            Browsers.open(this@TellerConnectActivity, request.url.toString())
            return true
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = inner.onPageStarted(view, url, favicon)
        override fun onPageFinished(view: WebView, url: String?) = inner.onPageFinished(view, url)
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = inner.shouldInterceptRequest(view, request)
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) = inner.onReceivedError(view, request, error)
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) = inner.onReceivedHttpError(view, request, response)
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()
    }

    /** Console output from the page is swallowed, so nothing from Connect reaches the system log. */
    private class GuardChrome(private val inner: WebChromeClient?) : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true
        override fun onProgressChanged(view: WebView?, newProgress: Int) { inner?.onProgressChanged(view, newProgress) }
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) =
            inner?.onCreateWindow(view, isDialog, isUserGesture, resultMsg) ?: false
        override fun onCloseWindow(window: WebView?) { inner?.onCloseWindow(window) }
    }

    override fun onInit() {}
    override fun onEvent(name: String, data: Map<String, Any>) {}
    override fun onSuccess(payment: Payment) = done(RESULT_CANCELED)
    override fun onSuccess(payee: Payee) = done(RESULT_CANCELED)

    override fun onSuccess(registration: Registration) {
        TellerHandoff.offer(registration.accessToken, registration.enrollment.id, registration.enrollment.institution.name)
        done(RESULT_OK)
    }

    override fun onExit() = done(RESULT_CANCELED)

    override fun onFailure(error: Error) {
        TellerHandoff.fail(error.message.ifBlank { "Teller Connect couldn't finish." })
        done(RESULT_CANCELED)
    }

    private fun done(code: Int) {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        setResult(code)
        finish()
    }

    companion object {
        private const val EXTRA_APP_ID = "app_id"
        private const val EXTRA_ENV = "environment"
        private const val EXTRA_ENROLLMENT = "enrollment_id"

        fun isTeller(u: Uri): Boolean {
            val host = u.host?.lowercase() ?: return false
            return u.scheme == "https" && (host == "teller.io" || host.endsWith(".teller.io"))
        }

        fun intent(context: Context, appId: String, environment: String, enrollmentId: String? = null) =
            Intent(context, TellerConnectActivity::class.java)
                .putExtra(EXTRA_APP_ID, appId).putExtra(EXTRA_ENV, environment).putExtra(EXTRA_ENROLLMENT, enrollmentId)
    }
}

/** Hands the Teller Connect result to the app in memory, so the access token never travels in an Intent. */
object TellerHandoff {
    class Success(val accessToken: String, val enrollmentId: String, val institution: String)

    @Volatile private var success: Success? = null
    @Volatile private var failure: String? = null

    fun offer(accessToken: String, enrollmentId: String, institution: String) { success = Success(accessToken, enrollmentId, institution); failure = null }
    fun fail(message: String) { failure = message; success = null }

    /** Takes the result once. */
    fun take(): Pair<Success?, String?> = (success to failure).also { success = null; failure = null }
}
