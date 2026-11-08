package eu.stedb.mysyncthing.ui

import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.ui.Fixtures.snapshot
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The forms say what is wrong under the field and keep Save off until it is right; a changed form asks before Back drops it. */
class FormsTest : ScreenTest() {
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private val save get() = compose.onNodeWithText(str(R.string.action_save))

    @Test
    fun addDeviceRefusesAWrongCheckCharacter() {
        show { DeviceEditScreen(snapshot, "", "", vm, onBack = {}, onSave = { _, _, _ -> }) }
        val typo = Fixtures.server.replace("-", "").let { it.dropLast(1) + if (it.last() == 'A') 'B' else 'A' }
        field(str(R.string.device_id)).performTextReplacement(typo)
        compose.onNodeWithText(str(R.string.error_device_id)).assertExists()
        save.assertIsNotEnabled()
    }

    @Test
    fun addDeviceKnowsADeviceTypedAnotherWay() {
        show { DeviceEditScreen(snapshot, "", "", vm, onBack = {}, onSave = { _, _, _ -> }) }
        field(str(R.string.device_id)).performTextReplacement(Fixtures.laptop.replace("-", " ").lowercase())
        compose.onNodeWithText(str(R.string.folder_already_added)).assertExists()
        save.assertIsNotEnabled()
    }

    @Test
    fun addDeviceSavesTheCanonicalId() {
        var saved: String? = null
        val id = idFromData("NEWDEVICENEWDEVICENEWDEVICENEWDEVICENEWDEVICENEWDEVA")
        show { DeviceEditScreen(snapshot, "", "", vm, onBack = {}, onSave = { i, _, _ -> saved = i }) }
        field(str(R.string.device_id)).performTextReplacement(id.lowercase().replace("-", " "))
        save.assertIsEnabled().performClick()
        assertEquals(id, saved)
    }

    @Test
    fun changedFormAsksBeforeBack() {
        var left = false
        show { DeviceEditScreen(snapshot, "", "", vm, onBack = { left = true }, onSave = { _, _, _ -> }) }
        field(str(R.string.name_optional)).performTextReplacement("Laptop 2")
        compose.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        compose.onNodeWithText(str(R.string.discard_title)).assertExists()
        assertTrue(!left)
    }

    @Test
    fun untouchedFormLeavesAtOnce() {
        var left = false
        show { DeviceEditScreen(snapshot, "", "", vm, onBack = { left = true }, onSave = { _, _, _ -> }) }
        compose.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        assertTrue(left)
    }

    private val root = internalStorage

    private fun folderForm(disk: MapDisk, offer: String? = "holiday", onSave: (FolderDraft) -> Unit = {}) = show {
        CompositionLocalProvider(LocalDisk provides disk) {
            val from = offer?.let { Fixtures.laptop }
            // As the app does: a new folder starts as the first free "New folder".
            val name = if (offer == null) newFolderName(str(R.string.folder_new_name), pathBase(Folder()), snapshot.folders, disk::isDirectory) else ""
            FolderEditScreen(snapshot, draftFor(snapshot, offer, from, Folder(), name), from, pathBase(Folder()), onBack = {}, onSave = onSave)
        }
    }

    private val pathRow get() = compose.onNodeWithTag("folderPath")

    @Test
    fun acceptedFolderGetsASuggestedPathInSharedStorage() {
        var saved: FolderDraft? = null
        folderForm(fixtureDisk()) { saved = it }
        pathRow.assert(hasText("Syncthing/Holiday 2026", substring = true))
        compose.onNodeWithText(str(R.string.folder_path_suggested)).assertExists()
        // Documents is synced already, and DCIM holds Photos: neither can be chosen.
        compose.onNodeWithText(str(R.string.folder_pick_synced, "Documents")).assertIsNotEnabled()
        compose.onNodeWithText("Download").performClick()
        pathRow.assert(hasText("Download", substring = true))
        compose.onNodeWithText(str(R.string.folder_path_created)).assertExists()
        save.assertIsEnabled().performClick()
        assertEquals("$root/Download", saved?.path)
        assertEquals("Holiday 2026", saved?.label)
    }

    /** New folder is a dialog with a text field, which Robolectric does not get idle with: the e2e test (browseFolder) and the PC walk cover it. */
    @Test
    fun browserPicksDownload() {
        var saved: FolderDraft? = null
        folderForm(fixtureDisk(), offer = null) { saved = it }
        // Ready to save as it opens: "New folder" in Syncthing/, which the quick picks don't repeat.
        field(str(R.string.name)).assert(hasText("New folder"))
        pathRow.assert(hasText("Syncthing/New folder", substring = true))
        compose.onNodeWithText(str(R.string.folder_path_suggested)).assertExists()
        quickPick("Syncthing/New folder").assertCountEquals(0)
        compose.onNodeWithText(str(R.string.folder_quick_picks)).assertExists()
        save.assertIsEnabled()
        compose.onNodeWithTag("browse").performClick()
        // Opens in Syncthing/, the default location, which is not a folder itself; the top of internal storage is one tap away.
        compose.onNodeWithText(str(R.string.error_path_default_location)).assertExists()
        compose.onNodeWithTag("browseUse").assertIsNotEnabled()
        compose.onAllNodesWithText(str(R.string.internal_storage)).onFirst().performClick()
        compose.onNodeWithText(str(R.string.browse_default_location)).assertExists()
        compose.onNodeWithText("Download").performClick()
        compose.onNodeWithTag("browseUse").assertIsEnabled().performClick()
        pathRow.assert(hasText("Download", substring = true))
        // The name was never typed, so the picked place names the folder; Syncthing/<name> is a quick pick again.
        field(str(R.string.name)).assert(hasText("Download"))
        quickPick("Syncthing/Download").assertCountEquals(1)
        save.assertIsEnabled().performClick()
        assertEquals("$root/Download", saved?.path)
        assertEquals("Download", saved?.label)
    }

    private fun quickPick(name: String) = compose.onAllNodes(hasText(name) and hasAnyAncestor(hasTestTag("quickPicks")))

    @Test
    fun typingANewNameReplacesNewFolder() {
        var saved: FolderDraft? = null
        folderForm(fixtureDisk(), offer = null) { saved = it }
        field(str(R.string.name)).performClick()
        field(str(R.string.name)).performTextInput("Recipes")
        field(str(R.string.name)).assert(hasText("Recipes"))
        pathRow.assert(hasText("Syncthing/Recipes", substring = true))
        // A typed name stays when a place is picked, and Syncthing/Recipes comes back as a quick pick.
        quickPick("Download").onFirst().performClick()
        field(str(R.string.name)).assert(hasText("Recipes"))
        pathRow.assert(hasText("Download", substring = true))
        quickPick("Syncthing/Recipes").onFirst().performClick()
        save.assertIsEnabled().performClick()
        assertEquals("$root/Syncthing/Recipes", saved?.path)
        assertEquals("Recipes", saved?.label)
    }

    @Test
    fun newFolderIsNumberedWhenTaken() {
        folderForm(fixtureDisk().apply { dirs["$root/Syncthing/New folder"] = 3 }, offer = null)
        field(str(R.string.name)).assert(hasText("New folder 2"))
        pathRow.assert(hasText("Syncthing/New folder 2", substring = true))
    }

    @Test
    fun browserRefusesAroundASyncedFolderAndAndroidData() {
        var picked: String? = null
        show { CompositionLocalProvider(LocalDisk provides fixtureDisk()) { FolderBrowser("$root/DCIM", "$root/Syncthing", snapshot.folders, onBack = {}) { picked = it } } }
        compose.onNodeWithText(str(R.string.browse_holds_synced, "Photos")).assertExists()
        compose.onNodeWithText(str(R.string.browse_synced_as, "Photos")).assertExists()
        compose.onNodeWithTag("browseUse").assertIsNotEnabled()
        compose.onAllNodesWithText(str(R.string.internal_storage)).onFirst().performClick()
        compose.onNodeWithText("Android").performClick()
        compose.onNodeWithText("data").assertHasNoClickAction()
        compose.onNodeWithText("media").performClick()
        compose.onNodeWithTag("browseUse").assertIsEnabled().performClick()
        assertEquals("$root/Android/media", picked)
    }

    /** A folder of 40 folders in a folder of 40, read on another thread as on a phone. */
    private fun longDisk(): Disk {
        val outer = (1..40).map { "$root/Long/sub%02d".format(it) }
        val inner = (1..40).map { "$root/Long/sub40/c%02d".format(it) }
        val disk = MapDisk(listOf(StorageRoot(root)), (outer + inner + "$root/Long/sub40/.cache").associateWith { 0 } + ("$root/Long" to 40))
        return object : Disk by disk { override val reads = Dispatchers.Default }
    }

    /** The row is the list's first, whole: its top not above the list's. */
    private fun assertAtTop(name: String) {
        val list = compose.onNodeWithTag("browseList").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        assertTrue("$name at ${row.top}, list at ${list.top}", row.top >= list.top && row.top - list.top < row.height * 2)
    }

    private fun waitForRow(name: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }

    /** Each folder opens at its top, not at the parent's position or one row down; hidden folders, sorted first, come into view. */
    @Test
    fun browserOpensEachFolderAtItsTop() {
        show { CompositionLocalProvider(LocalDisk provides longDisk()) { FolderBrowser("$root/Long", root, emptyList(), onBack = {}, onPick = {}) } }
        waitForRow("sub01")
        compose.onNodeWithTag("browseList").performScrollToKey("$root/Long/sub40")
        compose.onNodeWithText("sub40").performClick()
        waitForRow("c01")
        assertAtTop("c01")
        compose.onNodeWithTag("browseMenu").performClick()
        compose.onNodeWithText(str(R.string.browse_show_hidden)).performClick()
        compose.onNodeWithText(".cache").assertIsDisplayed()
    }

    /** A rotation reads the folder again; the list comes back where it was. */
    @Test
    fun browserKeepsItsPlaceOverARotation() {
        val restore = StateRestorationTester(compose)
        val disk = longDisk()
        restore.setContent { AppTheme { CompositionLocalProvider(LocalDisk provides disk) { FolderBrowser("$root/Long/sub40", root, emptyList(), onBack = {}, onPick = {}) } } }
        waitForRow("c01")
        compose.onNodeWithTag("browseList").performScrollToKey("$root/Long/sub40/c40")
        restore.emulateSavedInstanceStateRestore()
        waitForRow("c40")
        compose.onNodeWithText("c40").assertIsDisplayed()
    }

    @Test
    fun proxyRefusesABadHostAndPort() {
        show { ProxyScreen(vm, Fixtures.core, onBack = {}) }
        compose.onNodeWithText(str(R.string.proxy_use_tor)).performClick()
        field(str(R.string.proxy_host)).performTextReplacement("a..b")
        compose.onNodeWithText(str(R.string.error_host)).assertExists()
        field(str(R.string.proxy_host)).performTextReplacement("127.0.0.1")
        field(str(R.string.proxy_port)).performTextReplacement("65536")
        compose.onNodeWithText(str(R.string.error_port)).performScrollTo().assertExists()
        save.assertIsNotEnabled()
    }

    @Test
    fun exportPasswordNeedsTwelveCharactersTwice() {
        show { ExportScreen(onBack = {}) }
        field(str(R.string.password)).performTextReplacement("short")
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.error_password_short, MIN_EXPORT_PASSWORD, MIN_EXPORT_PASSWORD)).assertExists()
        field(str(R.string.password)).performTextReplacement("correct horse battery")
        field(str(R.string.password_again)).performTextReplacement("correct horse")
        compose.onNodeWithText(str(R.string.error_password_repeat)).assertExists()
        compose.onNodeWithText(str(R.string.export_choose)).assertIsNotEnabled()
    }

    /** Strong is the default; an encrypted format can't be saved without a password, so Save starts off. */
    @Test
    fun exportStartsStrongAndNeedsAPassword() {
        show { ExportScreen(onBack = {}) }
        compose.onNodeWithTag("export_Strong").onChild().assertIsSelected()
        compose.onNodeWithText(str(R.string.export_strong_note)).assertExists()
        compose.onNodeWithText(str(R.string.export_choose)).performScrollTo().assertIsNotEnabled()
        field(str(R.string.password)).performTextReplacement("correct horse battery")
        field(str(R.string.password_again)).performTextReplacement("correct horse battery")
        compose.onNodeWithText(str(R.string.export_choose)).performScrollTo().assertIsEnabled()
    }

    /** Compatible warns that the archive is vulnerable and still needs the 12-character password. */
    @Test
    fun exportCompatibleWarnsAndNeedsAPassword() {
        show { ExportScreen(onBack = {}) }
        compose.onNodeWithTag("export_Compatible").onChild().performClick()
        compose.onNodeWithText(str(R.string.export_compatible_warning)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.export_strong_note)).assertDoesNotExist()
        field(str(R.string.password)).performTextReplacement("elevenchars")
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.error_password_short, MIN_EXPORT_PASSWORD, MIN_EXPORT_PASSWORD)).assertExists()
        compose.onNodeWithText(str(R.string.export_choose)).performScrollTo().assertIsNotEnabled()
    }

    /** None hides the password fields, warns, and saves without a password even if one was typed before. */
    @Test
    fun exportNoneNeedsNoPassword() {
        show { ExportScreen(onBack = {}) }
        field(str(R.string.password)).performTextReplacement("short")
        compose.onNodeWithTag("export_None").onChild().performClick()
        field(str(R.string.password)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.export_no_password_warning)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.export_choose)).performScrollTo().assertIsEnabled()
    }

    /** A password shown in clear text keeps the screen out of screenshots, recordings and Recents; hidden again, it doesn't. */
    @Test
    fun shownPasswordHidesTheScreenFromCaptures() {
        show { ExportScreen(onBack = {}) }
        fun secure() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        assertTrue(!secure())
        compose.onNodeWithContentDescription(str(R.string.password_show)).performClick()
        compose.waitForIdle()
        assertTrue(secure())
        compose.onNodeWithContentDescription(str(R.string.password_hide)).performClick()
        compose.waitForIdle()
        assertTrue(!secure())
    }
}
