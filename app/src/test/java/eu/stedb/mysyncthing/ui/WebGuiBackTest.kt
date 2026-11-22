package eu.stedb.mysyncthing.ui

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * Back on the web GUI goes back inside the GUI while it has a page to go back to and closes the
 * screen after that, also once the screen was drawn again. MainActivity handles a rotation itself,
 * so the screen's function runs again while its WebView stays; up to 0.5.0 the screen had forgotten
 * its WebView by then, and Back closed it.
 */
class WebGuiBackTest : ScreenTest() {
    private var landscape by mutableStateOf(false)
    private var closed = 0

    /** The screen as the app shows it; [landscape] turns it as a rotation does, with a new configuration. */
    private fun gui(): WebView {
        show {
            val turned = Configuration(LocalConfiguration.current).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides turned) { WebGuiScreen(Fixtures.core, onClose = { closed++ }) }
        }
        compose.waitForIdle()
        return checkNotNull(webViewIn(compose.activity.window.decorView)) { "no WebView on the screen" }
    }

    private fun webViewIn(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { webViewIn(view.getChildAt(it)) }
        else -> null
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun backGoesBackInTheGuiAfterARotation() {
        val web = gui()
        // The GUI, then a page it linked to.
        val origin = "https://${Fixtures.core.address}"
        shadowOf(web).pushEntryToHistory("$origin/")
        shadowOf(web).pushEntryToHistory("$origin/#/page")
        assertTrue(web.canGoBack())

        landscape = true
        compose.waitForIdle()
        assertTrue("the same WebView after the rotation", webViewIn(compose.activity.window.decorView) === web)

        back()
        assertEquals("Back closed the screen though the GUI had a page to go back to", 0, closed)
        // Through whatever history is left, then out.
        repeat(3) { if (web.canGoBack()) back() }
        assertFalse(web.canGoBack())
        assertEquals(0, closed)
        back()
        assertEquals(1, closed)
    }

    @Test
    fun backClosesTheScreenOnTheGuisFirstPage() {
        val web = gui()
        assertFalse(web.canGoBack())
        back()
        assertEquals(1, closed)
    }
}
