package eu.stedb.mysyncthing.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.SyncthingService
import eu.stedb.mysyncthing.core.resolve
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(val route: String, @param:StringRes val label: Int, val icon: ImageVector) {
    Folders("folders", R.string.tab_folders, Icons.Default.Folder),
    Devices("devices", R.string.tab_devices, Icons.Default.Devices),
    Settings("settings", R.string.tab_settings, Icons.Default.Settings),
}

/** A screen opened from outside the app: the widget or a request notification. */
sealed interface LaunchTarget {
    data class Tab(val route: String) : LaunchTarget
    data class ReviewFolder(val folder: String, val device: String) : LaunchTarget
    data class ReviewDevice(val device: String) : LaunchTarget
    data class OpenFolder(val id: String) : LaunchTarget
}

private object Routes {
    const val FOLDER = "folder/{id}"
    const val FOLDER_NEED = "folder/{id}/need"
    const val FOLDER_FAILED = "folder/{id}/failed"
    const val FOLDER_CHANGES = "folder/{id}/changes"
    const val FOLDER_IGNORES = "folder/{id}/ignores?first={first}"
    const val DEVICE = "device/{id}"
    const val DEVICE_NEEDS = "device/{id}/needs/{folder}"
    const val THIS_PHONE = "this-phone"
    const val ADD_FOLDER = "add-folder?offer={offer}&from={from}"
    const val ADD_DEVICE = "add-device?id={id}&name={name}"
    const val WEB_GUI = "webgui"
    const val IGNORED = "ignored"
    const val ABOUT = "about"
    const val FOLDER_VERSIONS = "folder/{id}/versions"
    const val FOLDER_RECENT = "folder/{id}/recent"
    const val FOLDER_DEFAULTS = "defaults/folder"
    const val DEFAULT_IGNORES = "defaults/ignores"
    const val DEVICE_DEFAULTS = "defaults/device"
    const val FORK_IMPORT = "import/fork"
    const val EXPORT = "export"
    const val PROXY = "proxy"
    const val SCHEDULE = "schedule"
    const val DEVICE_SCHEDULE = "device/{id}/schedule"

    fun folder(id: String) = "folder/${Uri.encode(id)}"
    fun ignores(id: String, first: Boolean = false) = "folder/${Uri.encode(id)}/ignores?first=$first"
    fun device(id: String) = "device/${Uri.encode(id)}"
    fun deviceSchedule(id: String) = "device/${Uri.encode(id)}/schedule"
    fun deviceNeeds(id: String, folder: String) = "device/${Uri.encode(id)}/needs/${Uri.encode(folder)}"
    fun addDevice(id: String = "", name: String = "") = "add-device?id=${Uri.encode(id)}&name=${Uri.encode(name)}"
    fun addFolder(offer: String? = null, from: String? = null) =
        "add-folder?offer=${Uri.encode(offer.orEmpty())}&from=${Uri.encode(from.orEmpty())}"
}

/**
 * A screen above a tab, given a back action that pops only that screen: once it is no longer on top,
 * the action does nothing. A screen can ask to leave twice. Remove leaves at once, and the screen,
 * still drawn while its exit animation runs, leaves again when the snapshot no longer has its folder
 * or device; a quick second tap on the back arrow does the same. A plain popBackStack() then popped
 * the screen below too: for a folder the Folders tab, the start of the back stack, which left a
 * blank screen under the tab bar; for a device the Devices tab, which showed Folders.
 */
internal fun NavGraphBuilder.screen(
    nav: NavController,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (entry: NavBackStackEntry, back: () -> Unit) -> Unit,
) = composable(route, arguments) { entry ->
    val back = remember(entry) { { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() } }
    content(entry, back)
}

/** Logcat tag and message of [ReportEmptyBackStack]; scripts/e2e/run.py fails a run on them. */
internal const val NAV_LOG_TAG = "AppNav"
internal const val EMPTY_BACK_STACK = "back stack empty: blank screen"

/**
 * Logs an error when the back stack, once filled, empties: the screen is then blank, a bug no crash
 * check sees (the app stays alive and responsive). Only the step from filled to empty counts: the
 * stack is also empty before the graph is set, and nothing in the app empties it on purpose (Back
 * on a tab finishes the activity and leaves the stack as it is).
 *
 * currentBackStack is the only way to see the stack empty (currentBackStackEntryFlow and the
 * destination listeners stay silent then). Navigation marks it for its own library group; if a
 * later version stops filling it, LeaveScreenTest fails.
 */
@SuppressLint("RestrictedApi")
@Composable
internal fun ReportEmptyBackStack(nav: NavController) {
    LaunchedEffect(nav) {
        var filled = false
        nav.currentBackStack.collect { stack ->
            val screens = stack.count { it.destination !is NavGraph }
            if (screens > 0) filled = true
            else if (filled) Log.e(NAV_LOG_TAG, EMPTY_BACK_STACK)
        }
    }
}

@Composable
fun App(target: LaunchTarget? = null, onTargetShown: () -> Unit = {}, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    AppTheme {
        val permissions = rememberPermissionState()
        val setupDone by vm.setupDone.collectAsStateWithLifecycle()
        // All files access is required, so losing it brings the checklist back.
        if (!permissions.storage || setupDone == false) {
            val rc by vm.runConditions.collectAsStateWithLifecycle()
            SetupScreen(
                permissions, rc.mobileData, onMobileData = { v -> vm.setRunConditions { it.copy(mobileData = v) } }, onDone = vm::finishSetup,
            )
            return@AppTheme
        }
        if (setupDone == null) return@AppTheme
        // Each time the app comes to the front, not only at its first composition, so a failed start is
        // tried again. A Stop by the user holds: ON_START also comes with the screen turning on and with a theme,
        // language or font size change (they recreate the activity; a rotation does not, see the manifest).
        val scope = rememberCoroutineScope()
        LifecycleStartEffect(Unit) {
            scope.launch { SyncthingService.startUnlessStopped(context) }
            onStopOrDispose {}
        }

        val nav = rememberNavController()
        ReportEmptyBackStack(nav)
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
        val core by vm.core.collectAsStateWithLifecycle()
        val snapshot by vm.snapshot.collectAsStateWithLifecycle()
        val summary by vm.summary.collectAsStateWithLifecycle()
        val current by nav.currentBackStackEntryAsState()
        val route = current?.destination?.route
        val topLevel = Tab.entries.any { it.route == route }
        LaunchedEffect(target) {
            when (target) {
                // The tab at its top, as the tab bar opens it, not on top of the screen left open.
                is LaunchTarget.Tab -> Tab.entries.find { it.route == target.route }?.let {
                    nav.navigate(it.route) {
                        popUpTo(nav.graph.findStartDestination().id)
                        launchSingleTop = true
                    }
                }
                // Any app can start MainActivity with these extras, so only a real pending offer opens the form.
                is LaunchTarget.ReviewFolder -> {
                    val s = vm.snapshot.first { it.loaded }
                    if (s.pendingFolders[target.folder]?.offeredBy?.containsKey(target.device) == true) {
                        // A folder this phone already has is shared from its card on the Folders tab.
                        if (s.folders.any { it.id == target.folder }) nav.navigate(Tab.Folders.route) { launchSingleTop = true }
                        else nav.navigate(Routes.addFolder(target.folder, target.device))
                    }
                }
                is LaunchTarget.OpenFolder -> {
                    val s = vm.snapshot.first { it.loaded }
                    if (s.folders.any { it.id == target.id }) nav.navigate(Routes.folder(target.id))
                }
                is LaunchTarget.ReviewDevice -> {
                    val s = vm.snapshot.first { it.loaded }
                    s.pendingDevices[target.device]?.let { nav.navigate(Routes.addDevice(target.device, it.name)) }
                }
                null -> return@LaunchedEffect
            }
            onTargetShown()
        }

        UsageReportingPrompt(core, vm)
        MovedFromOtherPhoneNote(vm)

        // Each screen draws its own top bar; this Scaffold only adds the tab bar.
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            // Without the tab bar nothing lifts the snackbar above the system navigation bar.
            snackbarHost = {
                val sides = if (topLevel) WindowInsetsSides.Horizontal else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                SnackbarHost(snackbar, Modifier.windowInsetsPadding(SafeBars.only(sides)))
            },
            bottomBar = { if (topLevel) TabBar(nav, route, snapshot) },
        ) { padding ->
            NavHost(nav, startDestination = Tab.Folders.route, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                composable(Tab.Folders.route) {
                    CoreGate(core) {
                        FoldersScreen(
                            snapshot, summary, vm,
                            onOpen = { nav.navigate(Routes.folder(it)) },
                            onAdd = { nav.navigate(Routes.addFolder()) },
                            onAccept = { folder, device -> nav.navigate(Routes.addFolder(folder, device)) },
                            onOpenRecent = { nav.navigate("${Routes.folder(it)}/recent") },
                        )
                    }
                }
                composable(Tab.Devices.route) {
                    CoreGate(core) {
                        DevicesScreen(
                            snapshot, summary, vm, onOpen = { nav.navigate(Routes.device(it)) },
                            onOpenThisPhone = { nav.navigate(Routes.THIS_PHONE) },
                            onOpenSchedule = { nav.navigate(Routes.SCHEDULE) },
                            onAdd = { id, name -> nav.navigate(Routes.addDevice(id, name)) },
                        )
                    }
                }
                composable(Tab.Settings.route) {
                    SettingsScreen(
                        vm, snapshot, summary, core, permissions,
                        onOpenWebGui = { nav.navigate(Routes.WEB_GUI) }, onOpenIgnored = { nav.navigate(Routes.IGNORED) },
                        onOpenFolderDefaults = { nav.navigate(Routes.FOLDER_DEFAULTS) },
                        onOpenDeviceDefaults = { nav.navigate(Routes.DEVICE_DEFAULTS) },
                        onOpenAbout = { nav.navigate(Routes.ABOUT) },
                        onOpenImport = { nav.navigate(Routes.FORK_IMPORT) },
                        onOpenExport = { nav.navigate(Routes.EXPORT) },
                        onOpenProxy = { nav.navigate(Routes.PROXY) },
                    )
                }
                screen(nav, Routes.FOLDER) { entry, back ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    CoreGate(core) {
                        FolderScreen(
                            snapshot, id, vm, onBack = back,
                            onOpenNeed = { nav.navigate("${Routes.folder(id)}/need") },
                            onOpenFailed = { nav.navigate("${Routes.folder(id)}/failed") },
                            onOpenChanges = { nav.navigate("${Routes.folder(id)}/changes") },
                            onOpenIgnores = { nav.navigate(Routes.ignores(id)) },
                            onOpenVersions = { nav.navigate("${Routes.folder(id)}/versions") },
                        )
                    }
                }
                screen(nav, Routes.FOLDER_NEED) { entry, back ->
                    CoreGate(core) { NeedScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = back) }
                }
                screen(nav, Routes.FOLDER_FAILED) { entry, back ->
                    CoreGate(core) { FailedScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = back) }
                }
                screen(nav, Routes.FOLDER_CHANGES) { entry, back ->
                    CoreGate(core) { LocalChangesScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = back) }
                }
                screen(
                    nav, Routes.FOLDER_IGNORES, arguments = listOf(navArgument("first") { type = NavType.BoolType; defaultValue = false }),
                ) { entry, back ->
                    val args = entry.arguments
                    CoreGate(core) {
                        IgnoresScreen(snapshot, args?.getString("id").orEmpty(), args?.getBoolean("first") == true, vm, onBack = back)
                    }
                }
                screen(nav, Routes.DEVICE) { entry, back ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    CoreGate(core) {
                        DeviceScreen(
                            snapshot, id, vm, onBack = back, onOpenNeeds = { nav.navigate(Routes.deviceNeeds(id, it)) },
                            onOpenSchedule = { nav.navigate(Routes.deviceSchedule(id)) },
                        )
                    }
                }
                screen(nav, Routes.DEVICE_NEEDS) { entry, back ->
                    val args = entry.arguments
                    CoreGate(core) {
                        RemoteNeedScreen(snapshot, args?.getString("id").orEmpty(), args?.getString("folder").orEmpty(), vm, onBack = back)
                    }
                }
                screen(nav, Routes.THIS_PHONE) { _, back ->
                    CoreGate(core) { ThisPhoneScreen(snapshot, vm, onBack = back) }
                }
                screen(nav, Routes.ADD_FOLDER) { entry, back ->
                    val offer = entry.arguments?.getString("offer")?.ifEmpty { null }
                    val from = entry.arguments?.getString("from")?.ifEmpty { null }
                    CoreGate(core) {
                        // Read fresh for a new form; a recreated form has it on its first frame (kept in the view
                        // model). A failed read falls back to the app's own defaults.
                        var read by rememberSaveable { mutableStateOf(false) }
                        LaunchedEffect(Unit) { vm.defaultFolder.load(); read = true }
                        val defaults by vm.defaultFolder.value.collectAsStateWithLifecycle()
                        val base = if (read) defaults ?: Folder() else null
                        // "New folder", or the first free "New folder N", looked up on the disk off the main thread.
                        val disk = rememberDisk()
                        val newFolder = stringResource(R.string.folder_new_name)
                        var newName by rememberSaveable { mutableStateOf<String?>(null) }
                        LaunchedEffect(snapshot.loaded, base) {
                            if (newName == null && snapshot.loaded && base != null) {
                                newName = if (offer != null) "" else withContext(disk.reads) { newFolderName(newFolder, pathBase(base), snapshot.folders, disk::isDirectory) }
                            }
                        }
                        val name = newName
                        if (snapshot.loaded && base != null && name != null) {
                            // Saved, so a recreated screen keeps the same new folder ID and the form still counts as unchanged.
                            val initial = rememberSaveable(offer, from, saver = draftSaver) { draftFor(snapshot, offer, from, base, name) }
                            FolderEditScreen(snapshot, initial, from, pathBase(base), onBack = back) {
                                vm.addFolder(it, paused = it.ignoresFirst)
                                if (it.ignoresFirst) {
                                    nav.navigate(Routes.ignores(it.id.trim(), first = true)) { popUpTo(Routes.ADD_FOLDER) { inclusive = true } }
                                } else {
                                    back()
                                }
                            }
                        }
                    }
                }
                screen(nav, Routes.ADD_DEVICE) { entry, back ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val name = entry.arguments?.getString("name").orEmpty()
                    CoreGate(core) {
                        if (snapshot.loaded) {
                            DeviceEditScreen(snapshot, id, name, vm, onBack = back) { newId, newName, folders ->
                                vm.addDevice(newId, newName, folders)
                                back()
                            }
                        }
                    }
                }
                screen(nav, Routes.FOLDER_DEFAULTS) { _, back ->
                    CoreGate(core) {
                        DefaultFolderScreen(vm, onBack = back, onOpenIgnores = { nav.navigate(Routes.DEFAULT_IGNORES) })
                    }
                }
                screen(nav, Routes.DEFAULT_IGNORES) { _, back ->
                    CoreGate(core) { IgnoresScreen(snapshot, null, firstSync = false, vm, onBack = back) }
                }
                screen(nav, Routes.DEVICE_DEFAULTS) { _, back ->
                    CoreGate(core) { DefaultDeviceScreen(vm, onBack = back) }
                }
                screen(nav, Routes.FOLDER_RECENT) { entry, back ->
                    CoreGate(core) { RecentChangesScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = back) }
                }
                screen(nav, Routes.FOLDER_VERSIONS) { entry, back ->
                    CoreGate(core) { VersionsScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = back) }
                }
                screen(nav, Routes.ABOUT) { _, back -> AboutScreen(onBack = back) }
                // Not behind CoreGate: the import stops and restarts the core itself.
                screen(nav, Routes.FORK_IMPORT) { _, back -> ImportScreen(onBack = back) }
                screen(nav, Routes.EXPORT) { _, back -> ExportScreen(onBack = back) }
                screen(nav, Routes.PROXY) { _, back -> ProxyScreen(vm, core, onBack = back) }
                screen(nav, Routes.SCHEDULE) { _, back -> ScheduleScreen(vm, snapshot, onBack = back) }
                screen(nav, Routes.DEVICE_SCHEDULE) { entry, back ->
                    CoreGate(core) { ScheduleScreen(vm, snapshot, entry.arguments?.getString("id").orEmpty(), onBack = back) }
                }
                screen(nav, Routes.IGNORED) { _, back ->
                    CoreGate(core) { IgnoredScreen(vm, onBack = back) }
                }
                screen(nav, Routes.WEB_GUI) { _, back ->
                    // Each start brings a new address and login, which the WebView takes when it is made:
                    // a new run gets a new WebView, even if the state skipped from one run straight to the next.
                    // While the core is not running it shows the same stopped / starting screen as every other screen, not a blank page.
                    CoreGate(core) { (core as? CoreState.Running)?.let { key(it) { WebGuiScreen(it, onClose = back) } } }
                }
            }
        }
    }
}

/** Once after a phone migration: the old phone has the same device ID. */
@Composable
private fun MovedFromOtherPhoneNote(vm: MainViewModel) {
    val moved by vm.movedFromOtherPhone.collectAsStateWithLifecycle()
    if (!moved) return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.moved_title)) },
        text = { Text(stringResource(R.string.moved_text)) },
        confirmButton = { TextButton(onClick = vm::dismissMovedNote) { Text(stringResource(R.string.action_ok)) } },
    )
}

@Composable
private fun TabBar(nav: NavHostController, route: String?, s: Snapshot) {
    val folderRequests = s.pendingFolders.values.sumOf { it.offeredBy.size }
    NavigationBar(windowInsets = SafeBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)) {
        Tab.entries.forEach { tab ->
            val badge = when (tab) {
                Tab.Folders -> folderRequests
                Tab.Devices -> s.pendingDevices.size
                Tab.Settings -> 0
            }
            NavigationBarItem(
                selected = route == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(tab.icon, null) }
                },
                // One line: at a large font size a long word shrinks instead of breaking ("Impostazio-ni").
                label = { Text(stringResource(tab.label), maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = LocalTextStyle.current.fontSize)) },
            )
        }
    }
}
