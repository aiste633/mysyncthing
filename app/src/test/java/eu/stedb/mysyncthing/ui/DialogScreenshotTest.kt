package eu.stedb.mysyncthing.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.DiskSize
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowStatFs

/**
 * The six dialogs with a text field on a phone turned on its side (a window under 480 dp tall), where
 * [EntryDialog] fills the screen with Cancel and Save in a top bar: whole screen with the dialog window,
 * compared with the reference images in src/test/screenshots like [ScreenshotTest]. Upright they are
 * Material's centred dialog at the platform's default width, which Robolectric never gets idle with
 * (the window is measured at two widths every frame): the emulator checks those, and the keyboard.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class DialogScreenshotTest(private val dialog: Case, private val variant: Variant) {
    enum class Variant(val qualifiers: String, val fontScale: Float = 1f) {
        Light("+land-notnight"),
        Dark("+land-night"),
        GermanLargest("+de-land-notnight", fontScale = 2f),
    }

    class Case(val name: String, val ready: String? = null, val content: @Composable () -> Unit) {
        override fun toString() = name
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun screenshot() {
        RuntimeEnvironment.setQualifiers(variant.qualifiers)
        RuntimeEnvironment.setFontScale(variant.fontScale)
        compose.activityRule.scenario.recreate()
        ShadowStatFs.registerStats(DISK, 16_000_000, 4_000_000, 3_000_000)
        compose.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().background(AppColors.background))
                dialog.content()
            }
        }
        // The free space is read off the main thread: wait until it shows.
        dialog.ready?.let { text -> compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/dialog-${dialog.name}-landscape-${variant.name.lowercase()}.png")
    }

    companion object {
        const val DISK = "/storage/emulated/0/Photos"

        val cases = listOf(
            Case("text") { TextDialog(stringResource(R.string.folder_name), "Photos", stringResource(R.string.name), Limits.TEXT, onDismiss = {}) {} },
            Case("speed") { SpeedLimitDialog(stringResource(R.string.download_limit), 2_000, onDismiss = {}) {} },
            Case("addresses") { AddressesDialog(listOf("dynamic", "tcp://192.0.2.10:22000"), onDismiss = {}) {} },
            Case("password") { PasswordDialog("Photos", "Laptop", "", shared = true, onDismiss = {}, onUnshare = {}) {} },
            Case("diskfree", ready = "GB") { DiskFreeDialog(DiskSize(1.0, "%"), DISK, onDismiss = {}) {} },
            Case("time") { TimeDialog(stringResource(R.string.schedule_from_title), 22 * 60, onDismiss = {}) {} },
        )

        @JvmStatic
        @Parameters(name = "{0}-{1}")
        fun cases() = cases.flatMap { c -> Variant.entries.map { arrayOf<Any>(c, it) } }
    }
}
