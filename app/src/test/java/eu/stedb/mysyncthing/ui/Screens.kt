package eu.stedb.mysyncthing.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stedb.mysyncthing.core.ExportFormat
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
private val names = snapshot.devices.associate { it.deviceID to it.name }

val screens = listOf(
    ScreenCase("setup") { SetupScreen(PermissionState(LocalContext.current), mobileData = false, onMobileData = {}, onDone = {}) },
    ScreenCase("folders") { FoldersScreen(snapshot, summary, it, onOpen = {}, onAdd = {}, onAccept = { _, _ -> }, onOpenRecent = { _ -> }) },
    ScreenCase("folders-conflicts") {
        FoldersScreen(Fixtures.withConflicts, Fixtures.conflictsSummary, it, onOpen = {}, onAdd = {}, onAccept = { _, _ -> }, onOpenRecent = { _ -> })
    },
    ScreenCase("folder-syncing") { FolderScreen(snapshot, "docs", it, {}, {}, {}, {}, {}, {}) },
    ScreenCase("folder-error") { FolderScreen(snapshot, "music", it, {}, {}, {}, {}, {}, {}) },
    ScreenCase("folder-accept") {
        CompositionLocalProvider(LocalDisk provides fixtureDisk()) {
            FolderEditScreen(snapshot, draftFor(snapshot, "holiday", Fixtures.laptop, Folder()), Fixtures.laptop, pathBase(Folder()), onBack = {}, onSave = {})
        }
    },
    ScreenCase("browse-top") {
        CompositionLocalProvider(LocalDisk provides fixtureDisk()) { FolderBrowser(internalStorage, pathBase(Folder()), snapshot.folders, onBack = {}, onPick = {}) }
    },
    ScreenCase("browse-dcim") {
        CompositionLocalProvider(LocalDisk provides fixtureDisk()) { FolderBrowser("$internalStorage/DCIM", pathBase(Folder()), snapshot.folders, onBack = {}, onPick = {}) }
    },
    ScreenCase("devices") { DevicesScreen(snapshot, summary, it, onOpen = {}, onOpenThisPhone = {}, onOpenSchedule = {}, onAdd = { _, _ -> }) },
    ScreenCase("devices-blocked") {
        DevicesScreen(Fixtures.blocked, Fixtures.blockedSummary, it, onOpen = {}, onOpenThisPhone = {}, onOpenSchedule = {}, onAdd = { _, _ -> })
    },
    ScreenCase("devices-own-windows") {
        DevicesScreen(Fixtures.heldByOwn, Fixtures.heldByOwnSummary, it, onOpen = {}, onOpenThisPhone = {}, onOpenSchedule = {}, onAdd = { _, _ -> })
    },
    ScreenCase("devices-no-device") {
        DevicesScreen(Fixtures.waiting, Fixtures.noDeviceSummary, it, onOpen = {}, onOpenThisPhone = {}, onOpenSchedule = {}, onAdd = { _, _ -> })
    },
    ScreenCase("device") { DeviceScreen(snapshot, Fixtures.server, it, onBack = {}, onOpenNeeds = {}) },
    ScreenCase("device-blocked") { DeviceScreen(Fixtures.blocked, Fixtures.laptop, it, onBack = {}, onOpenNeeds = {}) },
    ScreenCase("device-own-windows") { DeviceScreen(Fixtures.heldByOwn, Fixtures.server, it, onBack = {}, onOpenNeeds = {}) },
    ScreenCase("device-never") { DeviceScreen(Fixtures.heldByOwn, Fixtures.tablet, it, onBack = {}, onOpenNeeds = {}) },
    ScreenCase("device-add") { DeviceEditScreen(snapshot, "", "", it, onBack = {}, onSave = { _, _, _ -> }) },
    ScreenCase("this-phone") { ThisPhoneScreen(snapshot, it, onBack = {}) },
    ScreenCase("settings") {
        SettingsScreen(it, snapshot, summary, core, PermissionState(LocalContext.current), {}, {}, {}, {}, {}, {}, {}, {})
    },
    ScreenCase("proxy") { ProxyScreen(it, core, onBack = {}) },
    ScreenCase("schedule") { ScheduleScreen(it, snapshot, onBack = {}) },
    ScreenCase("windows-global-short") { ScheduleForm(Fixtures.globalShort, null, names, save = { true }, onBack = {}) },
    ScreenCase("windows-device") { ScheduleForm(Fixtures.ownWindows, Fixtures.server, names, save = { true }, onBack = {}) },
    ScreenCase("windows-device-short") { ScheduleForm(Fixtures.shortOverlap, Fixtures.server, names, save = { true }, onBack = {}) },
    ScreenCase("windows-device-never") { ScheduleForm(Fixtures.noOverlap, Fixtures.server, names, save = { true }, onBack = {}) },
    ScreenCase("windows-device-default") { ScheduleForm(Fixtures.globalShort, Fixtures.laptop, names, save = { true }, onBack = {}) },
    ScreenCase("export") { ExportScreen(onBack = {}) },
    ScreenCase("export-compatible") { ExportScreen(onBack = {}, vm = viewModel<ExportViewModel>().apply { format = ExportFormat.Compatible }) },
    ScreenCase("export-none") { ExportScreen(onBack = {}, vm = viewModel<ExportViewModel>().apply { format = ExportFormat.None }) },
    ScreenCase("import") { ImportScreen(onBack = {}) },
)
