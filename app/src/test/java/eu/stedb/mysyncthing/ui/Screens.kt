package eu.stedb.mysyncthing.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.ui.Fixtures.core
import eu.stedb.mysyncthing.ui.Fixtures.snapshot
import eu.stedb.mysyncthing.ui.Fixtures.summary

/** One screen as the app shows it, with [Fixtures]; the name is its screenshot's file name. */
class ScreenCase(val name: String, val content: @Composable (MainViewModel) -> Unit) {
    override fun toString() = name
}

/**
 * The screens the screen tests walk. Screens that only show what they load from the
 * core (recent changes, versions, ignore patterns, defaults) are left out: without a
 * core they are empty. Dialogs are covered on the emulator (see androidTest).
 */
val screens = listOf(
    ScreenCase("setup") { SetupScreen(PermissionState(LocalContext.current), mobileData = false, onMobileData = {}, onDone = {}) },
    ScreenCase("folders") { FoldersScreen(snapshot, summary, it, onOpen = {}, onAdd = {}, onAccept = { _, _ -> }, onOpenRecent = {}) },
    ScreenCase("folder-syncing") { FolderScreen(snapshot, "docs", it, {}, {}, {}, {}, {}, {}) },
    ScreenCase("folder-error") { FolderScreen(snapshot, "music", it, {}, {}, {}, {}, {}, {}) },
    ScreenCase("folder-accept") {
        FolderEditScreen(snapshot, draftFor(snapshot, "holiday", Fixtures.laptop, Folder()), Fixtures.laptop, pathBase(Folder()), onBack = {}, onSave = {})
    },
    ScreenCase("devices") { DevicesScreen(snapshot, summary, it, onOpen = {}, onOpenThisPhone = {}, onAdd = { _, _ -> }) },
    ScreenCase("device") { DeviceScreen(snapshot, Fixtures.server, it, onBack = {}, onOpenNeeds = {}) },
    ScreenCase("device-add") { DeviceEditScreen(snapshot, "", "", it, onBack = {}, onSave = { _, _, _ -> }) },
    ScreenCase("this-phone") { ThisPhoneScreen(snapshot, it, onBack = {}) },
    ScreenCase("settings") {
        SettingsScreen(it, snapshot, summary, core, PermissionState(LocalContext.current), {}, {}, {}, {}, {}, {}, {}, {}, {})
    },
    ScreenCase("proxy") { ProxyScreen(it, core, onBack = {}) },
    ScreenCase("schedule") { ScheduleScreen(it, onBack = {}) },
    ScreenCase("export") { ExportScreen(onBack = {}) },
    ScreenCase("import") { ImportScreen(onBack = {}) },
)
