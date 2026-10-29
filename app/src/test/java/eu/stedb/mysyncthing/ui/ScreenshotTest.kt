package eu.stedb.mysyncthing.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment

/**
 * Every screen of [screens] in light and dark, at the largest font size and in Italian,
 * compared with the reference images in src/test/screenshots. A change that cuts text,
 * overlaps rows or breaks a colour shows up as a failed comparison with a diff image
 * (build/outputs/roborazzi). After a deliberate change: ./gradlew recordRoborazziDebug
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ScreenshotTest(private val screen: ScreenCase, private val variant: Variant) {
    enum class Variant(val qualifiers: String, val fontScale: Float = 1f) {
        Light("+notnight"),
        Dark("+night"),
        LargestFont("+notnight", fontScale = 2f),
        Italian("+it-notnight"),
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun screenshot() {
        RuntimeEnvironment.setQualifiers(variant.qualifiers)
        RuntimeEnvironment.setFontScale(variant.fontScale)
        compose.activityRule.scenario.recreate()
        val vm = MainViewModel(ApplicationProvider.getApplicationContext())
        compose.setContent { AppTheme { screen.content(vm) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/${screen.name}-${variant.name.lowercase()}.png")
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}-{1}")
        fun cases() = screens.flatMap { s -> Variant.entries.map { arrayOf<Any>(s, it) } }
    }
}
