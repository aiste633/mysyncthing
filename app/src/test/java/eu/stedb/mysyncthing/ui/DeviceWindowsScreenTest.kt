package eu.stedb.mysyncthing.ui

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.DeviceWindows
import eu.stedb.mysyncthing.core.MINUTES_PER_DAY
import eu.stedb.mysyncthing.core.Stretch
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.resolve
import eu.stedb.mysyncthing.core.strings
import eu.stedb.mysyncthing.core.text
import eu.stedb.mysyncthing.core.timeText
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.SyncWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import eu.stedb.mysyncthing.core.R as CoreR

/** The sync windows editor for one device and for all, and what the Devices tab shows of them. */
class DeviceWindowsScreenTest : ScreenTest() {
    private val server = Fixtures.server
    private val names = Fixtures.snapshot.devices.associate { it.deviceID to it.name }
    private var saved: RunConditions? = null

    private fun editor(initial: RunConditions, device: String?) = show {
        ScheduleForm(initial, device, names, save = { saved = it(initial); true }, onBack = {})
    }

    private val save get() = compose.onNodeWithTag("saveWindows")

    /** As the test's phone writes it (Robolectric: US English, 12 h clock). */
    private fun UiText.here() = resolve(app.strings(app.resources))

    /** A line of the result card under the windows. */
    private fun inResult(line: UiText) = compose.onNode(hasText(line.here()) and hasAnyAncestor(hasTestTag("windowsResult")), useUnmergedTree = true)

    @Test
    fun deviceEditorRefusesWindowsThatNeverOverlap() {
        editor(Fixtures.noOverlap, server)
        compose.onNodeWithText(str(R.string.windows_never)).performScrollTo().assertExists()
        // A change that still never overlaps: Save stays off.
        compose.onNodeWithText("Sat").performScrollTo().performClick()
        save.assertIsNotEnabled()
        // Off: the device follows the global windows again.
        compose.onNodeWithTag("ownWindows").performClick()
        save.assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(DeviceWindows(false, listOf(SyncWindow(21 * 60, 23 * 60, setOf(1, 2, 3, 4, 5, 7)))), saved!!.deviceWindows[server])
    }

    @Test
    fun deviceEditorWarnsOfShortStretchesButSaves() {
        editor(Fixtures.shortOverlap, server)
        compose.onNodeWithText(str(R.string.windows_short_title)).performScrollTo().assertExists()
        inResult(stretchText(Stretch(19 * 60 + 30, 30))).assertExists()
        compose.onNodeWithText("Sat").performScrollTo().performClick()
        save.assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(setOf(1, 2, 3, 4, 5, 6), saved!!.deviceWindows.getValue(server).windows.single().days)
        // The global windows and the other devices are left as they were.
        assertEquals(Fixtures.shortOverlap.windows, saved!!.windows)
    }

    @Test
    fun globalWindowsOnTheDeviceEditorAreReadOnly() {
        editor(Fixtures.ownWindows, server)
        compose.onNodeWithTag("globalWindows").assertHasNoClickAction()
        compose.onNodeWithText(str(R.string.windows_week, durationText(14 * 3600L).here())).performScrollTo().assertExists()
    }

    /** Global windows that leave a device without any time: named in a dialog, saved only on "Save anyway". */
    @Test
    fun globalSaveNamesDevicesThatWouldNeverSync() {
        val initial = RunConditions(
            timeSchedule = true,
            windows = listOf(SyncWindow(8 * 60, 12 * 60, (1..7).toSet()), SyncWindow(18 * 60, 20 * 60, (1..7).toSet())),
            deviceWindows = mapOf(server to DeviceWindows(true, listOf(SyncWindow(18 * 60, 23 * 60, (1..7).toSet())))),
        )
        editor(initial, null)
        compose.onNodeWithContentDescription(str(R.string.schedule_delete_window, 2)).performScrollTo().performClick()
        save.performClick()
        compose.onNodeWithText(str(R.string.schedule_never_title)).assertExists()
        compose.onNodeWithText("Home server").assertExists()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        assertNull(saved)
        save.performClick()
        compose.onNodeWithTag("saveAnyway").performClick()
        compose.waitForIdle()
        assertEquals(listOf(initial.windows.first()), saved!!.windows)
        // The device keeps its own windows: the user fixes them, or the global ones, later.
        assertEquals(initial.deviceWindows, saved!!.deviceWindows)
    }

    @Test
    fun globalEditorWarnsOfShortStretches() {
        editor(Fixtures.globalShort, null)
        compose.onNodeWithText(str(R.string.windows_short_title)).performScrollTo().assertExists()
        inResult(stretchText(Stretch(5 * MINUTES_PER_DAY + 12 * 60, 30))).assertExists()
    }

    @Test
    fun devicesTabShowsOwnHolds() {
        show { DevicesScreen(Fixtures.heldByOwn, Fixtures.heldByOwnSummary, vm, onOpen = {}, onOpenThisPhone = {}, onOpenSchedule = {}, onAdd = { _, _ -> }) }
        compose.onNodeWithContentDescription(str(R.string.device_pause_named, "Laptop")).assertIsEnabled()
        // Their own windows pause them: the button rests, as while a run condition pauses all.
        compose.onNodeWithContentDescription(str(R.string.device_pause_named, "Home server")).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.device_pause_named, "Old tablet")).assertIsNotEnabled()
        compose.onNode(hasText(text(CoreR.string.device_outside_next, timeText(18 * 60)).here(), substring = true)).assertExists()
        compose.onNode(hasText(str(CoreR.string.device_never_syncs), substring = true)).assertExists()
    }

    @Test
    fun deviceScreenOpensTheOwnWindows() {
        var opened = false
        show { DeviceScreen(Fixtures.heldByOwn, server, vm, onBack = {}, onOpenNeeds = {}, onOpenSchedule = { opened = true }) }
        compose.onNodeWithText(str(R.string.device_block_note_own)).assertExists()
        compose.onNodeWithTag("deviceWindows").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    @Test
    fun deviceScreenSaysNeverWithoutTheWaitingNote() {
        show { DeviceScreen(Fixtures.heldByOwn, Fixtures.tablet, vm, onBack = {}, onOpenNeeds = {}) }
        compose.onNodeWithText(str(CoreR.string.device_never_syncs)).assertExists()
        compose.onNodeWithText(str(R.string.device_block_note_own)).assertDoesNotExist()
    }
}
