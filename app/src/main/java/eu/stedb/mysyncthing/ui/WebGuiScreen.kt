package eu.stedb.mysyncthing.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.CoreState
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Upstream web GUI as a fallback for settings the native UI lacks. The first
 * request carries Basic auth; Syncthing then sets a session cookie. The user
 * never sees a login page or the login settings: only the app knows the login.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebGuiScreen(core: CoreState.Running, onClose: () -> Unit) {
    val origin = "http://${core.address}"
    val auth = "Basic " + Base64.getEncoder()
        .encodeToString("${core.credentials.guiUser}:${core.credentials.guiPassword}".toByteArray())
    var webView: WebView? = null
    BackHandler { webView?.takeIf { it.canGoBack() }?.goBack() ?: onClose() }
    AppScreen(title = stringResource(R.string.settings_web_gui), onBack = onClose) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        private var retries = 0
                        private var failed = false
                        private val refused = AtomicInteger()

                        // Stay on the local GUI; never send credentials elsewhere.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            return "${u.scheme}://${u.authority}" != origin
                        }

                        // Some settings (e.g. the theme) restart the GUI listener, and the GUI reloads
                        // into that gap of about 0.1 s. Chromium would draw its error page before
                        // onReceivedError below runs, so check the port first, on this background
                        // thread, and answer a refused page load with a blank page that retries
                        // itself. After about 30 s the load goes ahead and shows the real error.
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val u = request.url
                            if (!request.isForMainFrame || request.method != "GET" || "${u.scheme}://${u.authority}" != origin) return null
                            if (listening(u.host ?: return null, u.port)) {
                                refused.set(0)
                                return null
                            }
                            if (refused.incrementAndGet() > 120) return null
                            return WebResourceResponse("text/html", "utf-8", RETRY_PAGE.byteInputStream())
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            failed = false
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (!failed) retries = 0
                            if (url.startsWith("$origin/")) view.evaluateJavascript(HIDE_APP_MANAGED, null)
                        }

                        // The port went away between the check above and the load: retry for a while
                        // instead of staying on the error page.
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            val u = request.url
                            if (!request.isForMainFrame || "${u.scheme}://${u.authority}" != origin) return
                            if (error.errorCode != ERROR_CONNECT) return
                            failed = true
                            if (retries++ < 30) view.postDelayed({
                                if (view.isAttachedToWindow) view.loadUrl(u.toString(), mapOf("Authorization" to auth))
                            }, 1_000)
                        }

                        // Unhandled, a renderer crash would kill the whole process, and the sync core with it.
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            onClose()
                            return true
                        }
                    }
                    loadUrl("$origin/", mapOf("Authorization" to auth))
                    webView = this
                }
            },
            // Free the renderer when leaving, instead of letting it die with the view.
            onRelease = { it.stopLoading(); it.destroy() },
        )
    }
}

/** Whether something accepts connections on the loopback [port]. */
private fun listening(host: String, port: Int): Boolean =
    runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 500) } }.isSuccess

/** Shown while the GUI listener restarts: blank, in the system's light or dark, retrying every 250 ms. */
private const val RETRY_PAGE = """<!doctype html><meta name="color-scheme" content="light dark">
<script>setTimeout(function () { location.reload(); }, 250);</script>"""

/**
 * Hides what only the app may manage: Log Out (it would reload into the login
 * page), Shut Down (stopping is the service's job; Restart works in place),
 * and the GUI address, login, HTTPS and LDAP settings, which the core refuses
 * to change (the GUI could only show a generic error). Theme stays.
 */
private const val HIDE_APP_MANAGED = """(function () {
  var s = document.createElement('style');
  s.textContent = [
    'li:has(> a[ng-click="logout()"])',
    'li:has(> a[ng-click="shutdown()"])',
    '#settings-gui .form-group:has(#Address)',
    '#settings-gui .row:has(#user)',
    '#settings-gui .row:has(#UseTLS)',
    '#settings-general .form-group:has(button[ng-click="setAPIKey(tmpGUI)"])',
    '#advancedAccordion > .panel:has(#guiConfig)',
    '#advancedAccordion > .panel:has(#ldapConfig)',
  ].join(', ') + ' { display: none !important; }';
  document.head.appendChild(s);
})();"""
