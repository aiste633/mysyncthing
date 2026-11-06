package eu.stedb.mysyncthing.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.stedb.mysyncthing.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * The conflict badge on a folder card's Recent changes button is drawn whole at every font size and
 * count: it once sat inside an IconButton, whose 40 dp clip cut it from 1.3× font or two digits.
 * The count stays on one line and the button keeps its label where the badge covers the icon.
 * Robolectric renders natively here, so a clipped edge shows as the card's colour instead of amber.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(ParameterizedRobolectricTestRunner::class)
class BadgeFitTest(private val fontScale: Float, private val count: Int) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font {0}, {1} copies")
        fun cases() = listOf(1f, 1.3f, 1.8f, 2f).flatMap { f -> listOf(1, 12, 99, 123).map { arrayOf<Any>(f, it) } }
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theBadgeIsDrawnWholeInsideItsCard() {
        RuntimeEnvironment.setQualifiers("+en-w360dp-h800dp-notnight")
        RuntimeEnvironment.setFontScale(fontScale)
        compose.activityRule.scenario.recreate()
        val s = Fixtures.withConflicts.copy(conflicts = mapOf("docs" to (1..count).map { "f$it.sync-conflict-20251003-184200-EUYDWRS.txt" }))
        val vm = MainViewModel(ApplicationProvider.getApplicationContext())
        compose.setContent {
            AppTheme { FoldersScreen(s, Fixtures.conflictsSummary, vm, onOpen = {}, onAdd = {}, onAccept = { _, _ -> }, onOpenRecent = {}) }
        }
        val badge = compose.onNodeWithTag("badge-docs", useUnmergedTree = true)
        // The whole count on one line: the button's 40 dp once squeezed "99+" to "99".
        val text = compose.onNodeWithText(if (count > 99) "99+" else "$count", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        layouts.single().let { assertTrue("count laid out as ${it.lineCount} lines, ${it.getLineRight(0)} px in ${it.size}", it.lineCount == 1 && it.getLineRight(0) <= it.size.width) }
        // The button keeps its name where the badge covers the icon, and never says the number.
        compose.onNodeWithTag("recent-docs").assertContentDescriptionEquals(ApplicationProvider.getApplicationContext<android.app.Application>().getString(R.string.recent_changes))

        val b = badge.fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("recent-docs", useUnmergedTree = true).fetchSemanticsNode().let { button ->
            generateSequence(button.parent) { it.parent }.first { it.boundsInRoot.width > b.width * 4 }.boundsInRoot
        }
        assertTrue("badge $b outside its card $card", card.contains(b.topLeft) && card.contains(b.bottomRight - androidx.compose.ui.geometry.Offset(1f, 1f)))

        // The pill's fill at its ends and its top and bottom, a few pixels in from the edge.
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val amber = Color(0xFF8A5A00).toArgb()
        val r = b.height / 2
        val points = listOf(
            b.left + 3 to b.center.y, b.right - 4 to b.center.y,
            b.left + r to b.top + 3, b.right - r to b.top + 3,
            b.left + r to b.bottom - 4, b.right - r to b.bottom - 4,
        )
        for ((x, y) in points) {
            assertEquals("pixel at ($x, $y) of badge $b", Integer.toHexString(amber), Integer.toHexString(pixels[x.toInt(), y.toInt()].toArgb()))
        }
    }
}
