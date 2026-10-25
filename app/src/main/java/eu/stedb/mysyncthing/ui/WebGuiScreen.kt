package eu.stedb.mysyncthing.ui

import android.annotation.SuppressLint
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import eu.stedb.mysyncthing.core.CoreState
import java.util.Base64

/**
 * Upstream web GUI as a fallback for settings the native UI lacks. The first
 * request carries Basic auth; Syncthing then sets a session cookie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebGuiScreen(core: CoreState.Running, onClose: () -> Unit) {
    val origin = "http://${core.address}"
    val auth = "Basic " + Base64.getEncoder()
        .encodeToString("${core.credentials.guiUser}:${core.credentials.guiPassword}".toByteArray())
    var webView: WebView? = null
    BackHandler { webView?.takeIf { it.canGoBack() }?.goBack() ?: onClose() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Web GUI") },
            // The Scaffold around the screens already pads for the status bar.
            windowInsets = WindowInsets(0),
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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

