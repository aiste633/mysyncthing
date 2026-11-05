package eu.stedb.mysyncthing.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import eu.stedb.mysyncthing.R
import org.junit.Test
import eu.stedb.mysyncthing.core.R as CoreR

/**
 * The device cards' pause toggle and the device screen's switch, with syncing allowed and while
 * a run condition (mobile data) pauses everything.
 */
class DevicePauseScreenTest : ScreenTest() {
    private fun devices(blocked: Boolean) = show {
        val s = if (blocked) Fixtures.blocked else Fixtures.snapshot
        DevicesScreen(s, if (blocked) Fixtures.blockedSummary else Fixtures.summary, vm, onOpen = {}, onOpenThisPhone = {}, onOpenSchedule = {}, onAdd = { _, _ -> })
    }

    private val pauseSwitch get() = compose.onNode(isToggleable() and hasText(str(R.string.device_pause)))

    @Test
    fun cardsToggleTheUsersOwnPause() {
        devices(blocked = false)
        compose.onNodeWithContentDescription(str(R.string.device_pause_named, "Laptop")).assertIsEnabled().assertIsOff()
        compose.onNodeWithContentDescription(str(R.string.device_resume_named, "Old tablet")).assertIsEnabled().assertIsOn()
        compose.onNodeWithText(str(R.string.device_paused_by_you), substring = true).assertExists()
    }

    @Test
    fun whileBlockedCardsSayWhyAndTheToggleRests() {
        devices(blocked = true)
        compose.onNodeWithContentDescription(str(R.string.device_pause_named, "Laptop")).assertIsNotEnabled()
        compose.onNodeWithContentDescription(str(R.string.device_resume_named, "Old tablet")).assertIsNotEnabled()
        // What holds them wins over the tablet's own pause: that only decides once syncing is allowed.
        compose.onAllNodes(hasText(str(CoreR.string.pause_reason_mobile_data) + " · ", substring = true)).fetchSemanticsNodes().let {
            check(it.size == 3) { "every card says why, the tablet's too: ${it.size}" }
        }
        compose.onNodeWithText(str(R.string.device_paused_by_you), substring = true).assertDoesNotExist()
    }

    @Test
    fun whileBlockedTheDeviceScreenStillSetsTheUsersChoice() {
        show { DeviceScreen(Fixtures.blocked, Fixtures.laptop, vm, onBack = {}, onOpenNeeds = {}) }
        compose.onNodeWithText(str(CoreR.string.status_paused_because, str(CoreR.string.pause_reason_mobile_data))).assertExists()
        compose.onNodeWithText(str(R.string.device_block_note_conditions)).assertExists()
        pauseSwitch.performScrollTo().assertIsEnabled().assertIsOff()
    }

    /** The block is what it says while it holds; the switch shows the user's own pause, kept for after. */
    @Test
    fun ownPauseOutlastsTheBlock() {
        show { DeviceScreen(Fixtures.blocked, Fixtures.tablet, vm, onBack = {}, onOpenNeeds = {}) }
        compose.onNodeWithText(str(CoreR.string.status_paused_because, str(CoreR.string.pause_reason_mobile_data))).assertExists()
        compose.onNodeWithText(str(CoreR.string.pause_reason_user)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.device_block_note_conditions)).assertDoesNotExist()
        pauseSwitch.performScrollTo().assertIsEnabled().assertIsOn()
    }
}
