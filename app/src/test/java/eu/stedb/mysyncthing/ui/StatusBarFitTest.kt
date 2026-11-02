package eu.stedb.mysyncthing.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Summary
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.plural
import eu.stedb.mysyncthing.core.raw
import eu.stedb.mysyncthing.core.resolve
import eu.stedb.mysyncthing.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import eu.stedb.mysyncthing.core.R as CoreR

/**
 * The status in the tabs' top bar, in every language, at a phone's width (360 dp, the narrowest
 * common one, and a Pixel 7's 412 dp). At the largest font size the title keeps the bar and the
 * status gets its own row below, up to two lines: on a Pixel 7 every status the app shows reads in
 * full but the pause outside the sync times with its next time; narrower, the longest may be cut (the user's choice: the whole text is elsewhere), but
 * nothing runs into the content. At the normal size the title and the chip share one row.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class StatusBarFitTest(private val language: String, private val width: Int) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private var status by mutableStateOf(Summary(SyncState.UpToDate, text(CoreR.string.status_up_to_date)))

    private fun open(fontScale: Float) {
        RuntimeEnvironment.setQualifiers("+$language-w${width}dp-h800dp-notnight")
        RuntimeEnvironment.setFontScale(fontScale)
        compose.activityRule.scenario.recreate()
        compose.setContent {
            AppTheme {
                AppScreen(app.getString(R.string.tab_devices), status = status) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize().testTag("content"))
                }
            }
        }
    }

    /** The chip's text as laid out. */
    private fun layout(): TextLayoutResult {
        val node = compose.onNode(
            hasAnyAncestor(hasTestTag("statusRow")) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    @Test
    fun everyStatusFitsItsRowAtTheLargestFont() {
        open(2f)
        compose.onNode(hasText(app.getString(R.string.tab_devices)), useUnmergedTree = true).assertExists()
        for (t in STATUSES + LONG) {
            status = Summary(SyncState.Problem, t)
            compose.waitForIdle()
            val shown = t.resolve(app)
            val l = layout()
            assertEquals(shown, l.layoutInput.text.text)
            assertTrue("more than two lines: \"$shown\"", l.lineCount <= 2)
            // Ellipsized is cut; the width flag counts the chip hugging its text as overflow.
            if (width >= 412 && t !in LONG) assertFalse("cut: \"$shown\" ($language)", (0 until l.lineCount).any(l::isLineEllipsized))
            // The bar grew with the row: the content starts below it.
            val row = compose.onNodeWithTag("statusRow", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
            assertTrue("\"$shown\" runs into the content", row.bottom <= content.top)
        }
    }

    @Test
    fun oneRowAtTheNormalSize() {
        open(1f)
        status = Summary(SyncState.Problem, text(CoreR.string.status_no_device))
        compose.waitForIdle()
        compose.onNode(hasText(app.getString(R.string.tab_devices)), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(text(CoreR.string.status_no_device).resolve(app)), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("statusRow", useUnmergedTree = true).assertDoesNotExist()
    }

    companion object {
        private val REASONS = listOf(
            CoreR.string.pause_reason_user, CoreR.string.pause_reason_no_network, CoreR.string.pause_reason_mobile_data,
            CoreR.string.pause_reason_roaming, CoreR.string.pause_reason_metered_wifi, CoreR.string.pause_reason_metered_network,
            CoreR.string.pause_reason_not_charging, CoreR.string.pause_reason_charging, CoreR.string.pause_reason_battery_saver,
        )

        /** Every status of the overall summary (SyncStatus), with realistic values in place of names and counts. */
        val STATUSES: List<UiText> = listOf(
            CoreR.string.status_stopped, CoreR.string.status_stopped_error, CoreR.string.status_stopped_no_access,
            CoreR.string.status_failed_to_start, CoreR.string.status_starting, CoreR.string.status_paused,
            CoreR.string.status_no_network, CoreR.string.status_waiting, CoreR.string.status_no_device,
            CoreR.string.status_folder_error, CoreR.string.status_new_device, CoreR.string.status_new_folder,
            CoreR.string.status_no_devices, CoreR.string.status_no_folders, CoreR.string.status_syncing,
            CoreR.string.status_up_to_date, CoreR.string.schedule_outside,
        ).map { text(it) } +
            REASONS.map { text(CoreR.string.status_paused_because, text(it)) } +
            listOf(
                text(CoreR.string.status_syncing_percent, 99),
                text(CoreR.string.status_local_changes, "Documents"),
                text(CoreR.string.status_out_of_sync, "Documents"),
                plural(CoreR.plurals.status_problems, 12),
                plural(CoreR.plurals.status_items_failed, 120),
                plural(CoreR.plurals.status_requests, 3),
                plural(CoreR.plurals.status_folders_review, 3),
                // Every device outside its own sync windows.
                text(CoreR.string.status_paused_next, raw("22:00")),
                text(CoreR.string.status_paused_next_day, raw("Mo."), raw("22:00")),
            )

        /** May be cut even on a Pixel 7: the next sync time is on the Devices tab's Sync windows button. */
        val LONG: List<UiText> = listOf(
            text(CoreR.string.status_paused_because, text(CoreR.string.schedule_outside_next_day, raw("Mo."), raw("22:00"))),
        )

        @JvmStatic
        @Parameters(name = "{0}-{1}dp")
        fun cases() = listOf("en", "it", "es", "de", "fr").flatMap { l -> listOf(360, 412).map { arrayOf<Any>(l, it) } }
    }
}
