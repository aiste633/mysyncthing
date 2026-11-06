package eu.stedb.mysyncthing.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import kotlin.math.max
import kotlin.math.min

/**
 * Every text on every screen of [screens] stands out from what is behind it by at least 4.5:1
 * (WCAG AA), with the phone's usual palette and with a high-contrast one. Android's Colour
 * contrast setting (and some palettes, such as the API 35 emulator's) turns the container
 * colours dark in light mode with white text on them, and light in dark mode with dark text.
 * A text colour taken from one colour pair and drawn on another then vanishes: the Show ID and
 * Share ID labels were white on white that way. Measured on the rendered pixels, so it does not
 * matter which roles a screen picks; disabled controls are left out (they are faint by design).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ContrastTest(private val screen: ScreenCase, private val palette: Palette) {
    enum class Palette(val qualifiers: String, val highContrast: Boolean) {
        Light("+notnight", false),
        Dark("+night", false),
        LightHighContrast("+notnight", true),
        DarkHighContrast("+night", true),
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyTextIsReadable() {
        // A dense screen, so a glyph's strokes are several pixels wide and reach their full colour.
        RuntimeEnvironment.setQualifiers("${palette.qualifiers}-xxhdpi")
        compose.activityRule.scenario.recreate()
        val vm = MainViewModel(ApplicationProvider.getApplicationContext())
        val dark = palette.qualifiers == "+night"
        compose.setContent {
            val context = LocalContext.current
            val system = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            CompositionLocalProvider(LocalNow provides { Fixtures.NOW }) {
                AppTheme(if (palette.highContrast) system.highContrast() else system) { screen.content(vm) }
            }
        }
        compose.waitForIdle()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val window = Rect(0f, 0f, pixels.width.toFloat(), pixels.height.toFloat())
        val texts = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().descendants()
            .filter { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.isNotBlank() } }
            .filterNot { it.isDisabled() }
        assertTrue("${screen.name}: no text found", texts.isNotEmpty())
        val faint = texts.mapNotNull { n ->
            // Only text shown whole: a row cut off at the screen's edge shows just its padding.
            val box = n.boundsInRoot.intersect(window)
            if (box.width < max(1, n.size.width - 1) || box.height < max(1, n.size.height - 1)) return@mapNotNull null
            val ratio = textContrast(pixels, box)
            if (ratio < MIN_CONTRAST) "\"${n.config[SemanticsProperties.Text].joinToString(" ")}\" ${"%.2f".format(ratio)}:1" else null
        }
        assertTrue("${screen.name} ${palette.name}: text under $MIN_CONTRAST:1: $faint", faint.isEmpty())
    }

    /**
     * The background is the box's most common colour; the text is the pixel that differs from
     * it most (antialiased edges lie between the two).
     */
    private fun textContrast(pixels: PixelMap, box: Rect): Double {
        val counts = HashMap<Color, Int>()
        for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) {
            counts.merge(pixels[x, y], 1, Int::plus)
        }
        val background = counts.maxBy { it.value }.key
        return counts.keys.maxOf { contrast(it, background) }
    }

    private fun SemanticsNode.isDisabled(): Boolean =
        generateSequence(this) { it.parent }.any { SemanticsProperties.Disabled in it.config }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    companion object {
        const val MIN_CONTRAST = 4.5

        fun contrast(a: Color, b: Color): Double {
            val la = a.luminance() + 0.05
            val lb = b.luminance() + 0.05
            return max(la, lb) / min(la, lb)
        }

        /**
         * What Android's high Colour contrast does to a Material palette: each container takes
         * its accent's colour (dark in light mode, light in dark mode) and the accent's text colour.
         */
        fun ColorScheme.highContrast() = copy(
            primaryContainer = primary, onPrimaryContainer = onPrimary,
            secondaryContainer = secondary, onSecondaryContainer = onSecondary,
            tertiaryContainer = tertiary, onTertiaryContainer = onTertiary,
            errorContainer = error, onErrorContainer = onError,
        )

        @JvmStatic
        @Parameters(name = "{0}-{1}")
        fun cases() = screens.flatMap { s -> Palette.entries.map { arrayOf<Any>(s, it) } }
    }
}
