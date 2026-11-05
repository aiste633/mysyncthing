package eu.stedb.mysyncthing.ui

import android.view.WindowManager
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.ui.Fixtures.snapshot
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

    @Test
    fun acceptedFolderGetsASuggestedPathInSharedStorage() {
        show { FolderEditScreen(snapshot, draftFor(snapshot, "holiday", Fixtures.laptop, Folder()), Fixtures.laptop, pathBase(Folder()), onBack = {}, onSave = {}) }
        field(str(R.string.folder_path)).assert(hasText("/storage/emulated/0/Syncthing/Holiday 2026"))
        field(str(R.string.folder_path)).performTextReplacement("/data/data/eu.stedb.mysyncthing/files")
        compose.onNodeWithText(str(R.string.error_path_storage)).assertExists()
        save.assertIsNotEnabled()
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
