package eu.stedb.mysyncthing.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import eu.stedb.mysyncthing.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Leaving a folder or device screen pops that screen only, also when it asks twice: Remove leaves at
 * once, and the screen, still drawn during its exit animation, leaves again when the snapshot drops
 * the removed folder or device. With a plain popBackStack() the second call popped the tab below:
 * Folders, the start of the back stack, went blank under the tab bar; Devices gave way to Folders.
 *
 * The tabs and screens are the app's own, under the app's [screen]; the test drives the clock, so
 * the core's answer arrives while the screen is still leaving.
 */
class LeaveScreenTest : ScreenTest() {
    private lateinit var nav: NavHostController
    private var snapshot by mutableStateOf(Fixtures.snapshot)

    private fun app() = show {
        nav = rememberNavController()
        NavHost(nav, startDestination = FOLDERS) {
            composable(FOLDERS) {
                FoldersScreen(snapshot, Fixtures.summary, vm, onOpen = { nav.navigate("folder/$it") }, onAdd = {}, onAccept = { _, _ -> }, onOpenRecent = {})
            }
            composable(DEVICES) {
                DevicesScreen(snapshot, Fixtures.summary, vm, onOpen = { nav.navigate("device/$it") }, onOpenThisPhone = {}, onOpenSchedule = {}, onAdd = { _, _ -> })
            }
            screen(nav, "folder/{id}") { entry, back ->
                FolderScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, back, {}, {}, {}, {}, {})
            }
            screen(nav, "device/{id}") { entry, back ->
                DeviceScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = back, onOpenNeeds = {})
            }
        }
    }

    private val route get() = nav.currentBackStackEntry?.destination?.route

    /**
     * Confirms Remove, and 200 ms into the exit animation (NavHost's default fade takes 700 ms) the
     * snapshot loses what was removed, as when the core's ConfigSaved event comes back at once.
     */
    private fun remove(row: Int, removed: () -> Unit) {
        compose.onNodeWithText(str(row)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNode(hasText(str(R.string.action_remove)) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        compose.mainClock.advanceTimeBy(CORE_ANSWER_MS)
        removed()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun removedFolderLeavesTheFoldersTab() {
        app()
        compose.onNodeWithText("Photos").performClick()
        compose.waitForIdle()
        assertEquals("folder/{id}", route)

        remove(R.string.folder_remove) { snapshot = snapshot.copy(folders = snapshot.folders.filter { it.id != "photos" }) }

        assertEquals("back stack: ${nav.currentBackStack.value.map { it.destination.route }}", FOLDERS, route)
        compose.onNodeWithText("Documents").assertIsDisplayed()
    }

    @Test
    fun removedDeviceLeavesTheDevicesTab() {
        app()
        compose.runOnUiThread { nav.navigate(DEVICES) }
        compose.onNodeWithText("Laptop").performClick()
        compose.waitForIdle()
        assertEquals("device/{id}", route)

        remove(R.string.device_remove) { snapshot = snapshot.copy(devices = snapshot.devices.filter { it.deviceID != Fixtures.laptop }) }

        assertEquals("back stack: ${nav.currentBackStack.value.map { it.destination.route }}", DEVICES, route)
        compose.onNodeWithText("Home server").assertIsDisplayed()
    }

    /** Two taps on the back arrow before the screen is gone leave one screen. */
    @Test
    fun doubleTapOnBackLeavesOneScreen() {
        app()
        compose.onNodeWithText("Photos").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        repeat(2) { compose.onNodeWithContentDescription(str(R.string.action_back)).performClick() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals("back stack: ${nav.currentBackStack.value.map { it.destination.route }}", FOLDERS, route)
        compose.onNodeWithText("Documents").assertIsDisplayed()
    }

    private companion object {
        const val FOLDERS = "folders"
        const val DEVICES = "devices"
        const val CORE_ANSWER_MS = 200L
    }
}
