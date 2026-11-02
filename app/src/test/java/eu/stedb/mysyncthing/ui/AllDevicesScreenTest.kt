package eu.stedb.mysyncthing.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.Settings
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.resolve
import eu.stedb.mysyncthing.core.SyncWindow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import eu.stedb.mysyncthing.core.R as CoreR

/**
 * The Devices tab: the controls for all devices (pause and sync times, moved from Settings),
 * "last seen" on every card that is not connected, and the overall status while no device is.
 */
class AllDevicesScreenTest : ScreenTest() {
    private var scheduleOpened = 0

    private fun devices(s: eu.stedb.mysyncthing.core.Snapshot = Fixtures.snapshot, summary: eu.stedb.mysyncthing.core.Summary = Fixtures.summary) = show {
        DevicesScreen(s, summary, vm, onOpen = {}, onOpenThisPhone = {}, onOpenSchedule = { scheduleOpened++ }, onAdd = { _, _ -> })
    }

    private val settings by lazy { Settings(app) }
    private fun conditions() = runBlocking { settings.runConditions.first() }

    /** The app's settings outlive a test in this process. */
    @Before
    fun defaults() = runBlocking { check(settings.update { RunConditions() }) }

    /** As the phone shows it (the JVM's own date formats differ, e.g. "Sept"). */
    private fun UiText.shown() = resolve(app)

    @Test
    fun defaultsShowAsDefaults() {
        devices()
        compose.onNodeWithText(str(R.string.devices_all)).assertExists()
        compose.onNodeWithTag("pauseAll").assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNodeWithText(str(R.string.value_default, str(R.string.devices_pause_off))).assertExists()
        compose.onNodeWithTag("syncTimes").assertHasClickAction()
        compose.onNodeWithText(str(R.string.value_default, str(R.string.any_time))).assertExists()
    }

    /** Waits for [done], letting the main thread run the view model's writes meanwhile. */
    private fun settle(what: String, done: () -> Boolean) {
        repeat(100) {
            compose.waitForIdle()
            if (done()) return
            Thread.sleep(50)
        }
        throw AssertionError("not reached: $what")
    }

    @Test
    fun pauseIsAToggleOfTheSetting() {
        devices()
        compose.onNodeWithTag("pauseAll").performClick()
        settle("paused") { conditions().pausedByUser }
        settle("shown as on") { compose.onAllNodes(hasText(str(R.string.devices_pause_on))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pauseAll").assertIsOn().performClick()
        settle("resumed") { !conditions().pausedByUser }
        settle("shown as off") { compose.onAllNodes(hasText(str(R.string.value_default, str(R.string.devices_pause_off)))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pauseAll").assertIsOff()
    }

    @Test
    fun syncTimesOpenTheirScreen() {
        devices()
        compose.onNodeWithTag("syncTimes").performClick()
        assertEquals(1, scheduleOpened)
        assertEquals("the button only opens the screen", false, conditions().timeSchedule)
    }

    @Test
    fun setSyncTimesShowWithoutTheDefaultMark() {
        val day = SyncWindow(7 * 60, 22 * 60, (1..5).toSet())
        runBlocking { check(settings.update { it.copy(timeSchedule = true, windows = listOf(day)) }) }
        devices()
        val expected = scheduleText(conditions()).shown()
        settle("schedule shown") { compose.onAllNodes(hasText(expected)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(str(R.string.value_default, str(R.string.any_time))).assertDoesNotExist()
    }

    @Test
    fun settingsNoLongerHaveThem() {
        show { SettingsScreen(vm, Fixtures.snapshot, Fixtures.summary, Fixtures.core, PermissionState(app), {}, {}, {}, {}, {}, {}, {}, {}) }
        for (moved in listOf(R.string.devices_pause_all, R.string.sync_times, R.string.devices_sync_times)) {
            compose.onAllNodes(hasText(str(moved))).fetchSemanticsNodes().let { assertTrue("${str(moved)} still in Settings", it.isEmpty()) }
        }
    }

    @Test
    fun cardsSayWhenTheDeviceWasLastSeen() {
        devices()
        val tablet = lastSeenText(Fixtures.snapshot.lastSeen[Fixtures.tablet]).shown().replaceFirstChar { it.lowercase() }
        // Paused by the user, so not connected: the reason, then when it was seen.
        compose.onNodeWithText("${str(R.string.device_paused_by_you)} · $tablet", substring = true).assertExists()
        // Connected devices say how instead.
        compose.onNodeWithText(str(R.string.connection_lan) + " · ", substring = true).assertExists()
        compose.onAllNodes(hasText(str(R.string.device_last_seen, ""), substring = true, ignoreCase = true)).fetchSemanticsNodes().let {
            assertEquals("only the tablet's card", 1, it.size)
        }
    }

    @Test
    fun disconnectedCardsStartWithLastSeenOrNeverConnected() {
        devices(Fixtures.waiting, Fixtures.waitingSummary)
        val laptop = lastSeenText(Fixtures.waiting.lastSeen[Fixtures.laptop]).shown()
        compose.onNodeWithText("$laptop · ", substring = true).assertExists()
        compose.onNodeWithText(str(R.string.device_never_connected) + " · ", substring = true).assertExists()
        compose.onNodeWithText(str(CoreR.string.status_waiting)).assertExists()
    }

    @Test
    fun anHourWithoutDevicesIsAProblem() {
        devices(Fixtures.waiting, Fixtures.noDeviceSummary)
        compose.onNodeWithText(str(CoreR.string.status_no_device)).assertExists()
    }

    @Test
    fun whileBlockedCardsGiveTheReasonThenLastSeen() {
        devices(Fixtures.blocked, Fixtures.blockedSummary)
        val laptop = lastSeenText(Fixtures.blocked.lastSeen[Fixtures.laptop]).shown().replaceFirstChar { it.lowercase() }
        compose.onNodeWithText("${str(CoreR.string.pause_reason_mobile_data)} · $laptop", substring = true).assertExists()
    }

    @Test
    fun theDeviceScreenNoLongerSaysLastSeen() {
        show { DeviceScreen(Fixtures.blocked, Fixtures.laptop, vm, onBack = {}, onOpenNeeds = {}) }
        compose.onNodeWithText(str(R.string.device_pause)).performScrollTo()
        compose.onAllNodes(hasText(str(R.string.device_last_seen, ""), substring = true, ignoreCase = true)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onAllNodes(hasText(str(R.string.device_never_connected))).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }
}
