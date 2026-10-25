package eu.stedb.mysyncthing.ui

import android.annotation.SuppressLint
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import eu.stedb.mysyncthing.core.CoreState
import java.util.Base64

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
    AppScreen(title = "Web GUI", onBack = onClose) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        // Stay on the local GUI; never send credentials elsewhere.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            return "${u.scheme}://${u.authority}" != origin
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (url.startsWith("$origin/")) view.evaluateJavascript(HIDE_APP_MANAGED, null)
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
    '#advancedAccordion > .panel:has(#guiConfig)',
    '#advancedAccordion > .panel:has(#ldapConfig)',
  ].join(', ') + ' { display: none !important; }';
  document.head.appendChild(s);
})();"""
