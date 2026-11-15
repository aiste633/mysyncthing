package eu.stedb.mysyncthing.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The core memory limit dialog: automatic ticked saves null, an own value saves its MB, and a value out of
 * range keeps Save off. On a phone turned on its side, where the dialog fills the screen (see DialogScreenshotTest).
 */
@RunWith(RobolectricTestRunner::class)
class MemoryLimitDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var saved: Long? = -1

    @Before
    fun landscape() {
        RuntimeEnvironment.setQualifiers("+land-notnight")
        compose.activityRule.scenario.recreate()
    }

    private fun show(own: Long?) = compose.setContent {
        AppTheme { MemoryLimitDialog(own, phone, onDismiss = {}, read = { MemoryUse(24_000_000, 212_000_000) }) { saved = it } }
    }

    // A disabled text field takes no text: no SetText action.
    private val field get() = compose.onNode(hasSetTextAction())

    @Test
    fun automaticSavesNull() {
        show(own = 1000)
        compose.onNodeWithText("Automatic (773 MB)").performClick()
        field.assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        assertEquals(null, saved)
    }

    @Test
    fun ownValueSavesMegabytes() {
        show(own = null)
        field.assertDoesNotExist()
        compose.onNodeWithText("Automatic (773 MB)").performClick()
        field.assertExists()
        field.performTextReplacement("100")
        compose.onNodeWithText("From 256 MB to 3,980 MB, or 0 for no limit").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        field.performTextReplacement("0")
        compose.onNodeWithText("Save").performClick()
        assertEquals(0L, saved)
    }

    @Test
    fun memoryInUseIsShown() {
        show(own = null)
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("212 MB")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("24 MB").assertExists()
        compose.onNodeWithText("Android 17’s limit for this phone: 1,073 MB · refreshes every 5 s").assertExists()
    }

    companion object {
        /** A "4 GB" phone on Android 17: MemTotal 3,796 MiB (3,980 MB), Android's limit 1,024 MiB (1,073 MB). */
        val phone = MemoryPhone(37, (4096L - 300) shl 20)
    }
}
