package eu.stedb.mysyncthing.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Connections
import eu.stedb.mysyncthing.core.ServiceStatus
import eu.stedb.mysyncthing.core.SystemStatus
import eu.stedb.mysyncthing.core.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment

/**
 * The "This phone" card on the Devices tab keeps its height when the first poll answers: the
 * health line is there from the first frame with dashes, so "All devices" and the list below
 * never move. Every language, at a phone's width (360 dp and a Pixel 7's 412 dp), at the
 * normal and the largest font size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ThisDeviceCardTest(private val language: String, private val width: Int, private val fontScale: Float) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private var live by mutableStateOf<LiveStats?>(null)

    private fun open() {
        RuntimeEnvironment.setQualifiers("+$language-w${width}dp-h800dp-notnight")
        RuntimeEnvironment.setFontScale(fontScale)
        compose.activityRule.scenario.recreate()
        compose.setContent {
            AppTheme { Box(Modifier.testTag("card")) { ThisDeviceCard(Fixtures.snapshot, live, onOpen = {}, onShowQr = {}) } }
        }
        compose.waitForIdle()
    }

    private fun height(): Float = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.height

    private fun healthShows(text: String) =
        compose.onNode(hasAnyAncestor(hasTestTag("health")) and hasText(text), useUnmergedTree = true).assertExists()

    @Test
    fun theCardKeepsItsHeightWhenTheFirstPollAnswers() {
        open()
        healthShows(app.getString(R.string.health_pending))
        val before = height()
        for (stats in listOf(stats(3, 3, 4, 4), stats(1, 3, 2, 4))) {
            live = stats
            compose.waitForIdle()
            healthShows(stats.healthText.resolve(app))
            assertEquals("${stats.healthText.resolve(app)} ($language, $width dp, ×$fontScale)", before, height(), 0.5f)
        }
    }

    /** "Discovery off" is shorter: the card may lose a line at the largest font, never gain one. */
    @Test
    fun discoveryOffNeverGrowsTheCard() {
        open()
        val before = height()
        live = stats(3, 3, 0, 0).copy(status = stats(3, 3, 0, 0).status.copy(discoveryEnabled = false))
        compose.waitForIdle()
        assertTrue("$language, $width dp, ×$fontScale", height() <= before + 0.5f)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} {1}dp ×{2}")
        fun cases(): List<Array<Any>> =
            listOf("en", "it", "es", "de", "fr").flatMap { l -> listOf(360, 412).flatMap { w -> listOf(1f, 2f).map { arrayOf<Any>(l, w, it) } } }

        /** [listening] of [listeners] work, [found] of [methods] discovery methods. */
        fun stats(listening: Int, listeners: Int, found: Int, methods: Int) = LiveStats(
            null, emptyMap(), Connections(),
            SystemStatus(
                "ME",
                connectionServiceStatus = (0 until listeners).associate { "listener $it" to ServiceStatus(if (it < listening) null else "refused") },
                discoveryEnabled = true,
                discoveryStatus = (0 until methods).associate { "method $it" to ServiceStatus(if (it < found) null else "refused") },
            ),
        )
    }
}
