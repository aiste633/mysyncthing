package eu.stedb.mysyncthing.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import eu.stedb.mysyncthing.R
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog

/**
 * [EntryDialog] on a phone on its side: ✕ and Save on a bar at the top, Save off while the value
 * is wrong, ✕ and Back cancel. Upright it is Material's dialog, which Robolectric never gets idle
 * with; the switch between the two with a rotation (same window, text, cursor and keyboard kept) is
 * checked on the emulators by E2eTest.rotationKeepsActivityAndDialog.
 */
class EntryDialogTest : ScreenTest() {
    private var dismissed = 0
    private var saved: String? = null

    @Before
    fun landscape() {
        RuntimeEnvironment.setQualifiers("+land")
        compose.activityRule.scenario.recreate()
    }

    private fun nameDialog(initial: String = "Photos") = show {
        TextDialog(str(R.string.folder_name), initial, str(R.string.name), onDismiss = { dismissed++ }) { saved = it }
    }

    private val save get() = compose.onNode(hasText(str(R.string.action_save)) and hasClickAction())

    @Test
    fun shortWindowPutsCancelAndSaveOnTop() {
        nameDialog()
        assertTrue(app.resources.displayMetrics.run { heightPixels / density } < SHORT_WINDOW_DP)
        compose.onNode(isDialog()).assertIsDisplayed()
        val cancel = compose.onNodeWithContentDescription(str(R.string.action_cancel)).assertIsDisplayed()
        val field = compose.onNode(hasSetTextAction())
        // The bar is above the field, and nothing else says Cancel (no button row under it).
        val barBottom = cancel.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(barBottom <= field.fetchSemanticsNode().boundsInRoot.top)
        assertTrue(save.fetchSemanticsNode().boundsInRoot.bottom <= field.fetchSemanticsNode().boundsInRoot.top)
        assertEquals(0, compose.onAllNodes(hasText(str(R.string.action_cancel))).fetchSemanticsNodes().size)
        // The whole width of the screen, not Material's 560 dp at most.
        assertEquals(app.resources.displayMetrics.widthPixels.toFloat(), compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test
    fun saveIsOffWhileTheValueIsWrong() {
        nameDialog()
        save.assertIsEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        save.assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assert(hasText(str(R.string.name)))
        compose.onNode(hasSetTextAction()).performTextReplacement("Pictures")
        save.assertIsEnabled().performClick()
        assertEquals("Pictures", saved)
        assertEquals(0, dismissed)
    }

    @Test
    fun crossCancels() {
        nameDialog()
        compose.onNodeWithContentDescription(str(R.string.action_cancel)).performClick()
        assertEquals(1, dismissed)
        assertEquals(null, saved)
    }

    @Test
    fun backCancels() {
        nameDialog()
        compose.onNode(hasSetTextAction()).performTextReplacement("Pictures")
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(null, saved)
    }

    /** The password shown as plain text: the dialog's window is secure through its properties, which the dialog applies at each update. */
    @Test
    fun shownPasswordKeepsTheDialogSecure() {
        // A change of layout direction updates the dialog's window as a rotation does (DialogWrapper.updateParameters).
        var direction by mutableStateOf(LayoutDirection.Ltr)
        show {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                PasswordDialog("Photos", "Laptop", "", shared = false, onDismiss = { dismissed++ }, onUnshare = {}) { saved = it }
            }
        }
        fun secure() = ShadowDialog.getLatestDialog().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        assertFalse(secure())
        compose.onNode(hasSetTextAction()).performTextReplacement("secret")
        compose.onNodeWithContentDescription(str(R.string.password_show)).performClick()
        compose.waitForIdle()
        assertTrue(secure())
        direction = LayoutDirection.Rtl
        compose.waitForIdle()
        assertTrue("still secure after the dialog's window was updated", secure())
        compose.onNodeWithContentDescription(str(R.string.password_hide)).performClick()
        compose.waitForIdle()
        assertFalse(secure())
    }
}
