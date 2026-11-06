package eu.stedb.mysyncthing.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The folder browser on a phone turned on its side, where a list under the storages and
 * above the choice was one row tall: the choice moves beside the list. Like [ScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
class LandscapeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun browser() {
        RuntimeEnvironment.setQualifiers("+land-notnight")
        compose.activityRule.scenario.recreate()
        val vm = MainViewModel(ApplicationProvider.getApplicationContext())
        val case = screens.first { it.name == "browse-dcim" }
        compose.setContent { CompositionLocalProvider(LocalNow provides { Fixtures.NOW }) { AppTheme { case.content(vm) } } }
        compose.onRoot().captureRoboImage("src/test/screenshots/browse-dcim-landscape.png")
    }
}
