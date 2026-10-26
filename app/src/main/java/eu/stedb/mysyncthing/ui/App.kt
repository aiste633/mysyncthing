package eu.stedb.mysyncthing.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.SyncthingService
import kotlinx.coroutines.flow.first

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Folders("folders", "Folders", Icons.Default.Folder),
    Devices("devices", "Devices", Icons.Default.Devices),
    Settings("settings", "Settings", Icons.Default.Settings),
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
    const val RECENT = "recent"
    const val ABOUT = "about"
    const val FOLDER_VERSIONS = "folder/{id}/versions"
    const val FOLDER_DEFAULTS = "defaults/folder"
    const val DEFAULT_IGNORES = "defaults/ignores"
    const val DEVICE_DEFAULTS = "defaults/device"

    fun folder(id: String) = "folder/${Uri.encode(id)}"
    fun ignores(id: String, first: Boolean = false) = "folder/${Uri.encode(id)}/ignores?first=$first"
    fun device(id: String) = "device/${Uri.encode(id)}"
    fun deviceNeeds(id: String, folder: String) = "device/${Uri.encode(id)}/needs/${Uri.encode(folder)}"
    fun addDevice(id: String = "", name: String = "") = "add-device?id=${Uri.encode(id)}&name=${Uri.encode(name)}"
    fun addFolder(offer: String? = null, from: String? = null) =
        "add-folder?offer=${Uri.encode(offer.orEmpty())}&from=${Uri.encode(from.orEmpty())}"
}

@Composable
fun App(target: LaunchTarget? = null, onTargetShown: () -> Unit = {}, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    AppTheme {
        val permissions = rememberPermissionState()
        val setupDone by vm.setupDone.collectAsStateWithLifecycle()
        // All files access is required, so losing it brings the checklist back.
        if (!permissions.storage || setupDone == false) {
            SetupScreen(permissions, onDone = vm::finishSetup)
            return@AppTheme
        }
        if (setupDone == null) return@AppTheme
        LaunchedEffect(Unit) { SyncthingService.start(context) }

        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
        val core by vm.core.collectAsStateWithLifecycle()
        val snapshot by vm.snapshot.collectAsStateWithLifecycle()
        val summary by vm.summary.collectAsStateWithLifecycle()
        val current by nav.currentBackStackEntryAsState()
        val route = current?.destination?.route
        val topLevel = Tab.entries.any { it.route == route }
        LaunchedEffect(target) {
            when (target) {
                is LaunchTarget.Tab -> Tab.entries.find { it.route == target.route }?.let { nav.navigate(it.route) { launchSingleTop = true } }
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
                            onOpenRecent = { nav.navigate(Routes.RECENT) },
                        )
                    }
                }
                composable(Tab.Devices.route) {
                    CoreGate(core) {
                        DevicesScreen(
                            snapshot, summary, vm, onOpen = { nav.navigate(Routes.device(it)) },
                            onOpenThisPhone = { nav.navigate(Routes.THIS_PHONE) },
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
                    )
                }
                composable(Routes.FOLDER) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    CoreGate(core) {
                        FolderScreen(
                            snapshot, id, vm, onBack = { nav.popBackStack() },
                            onOpenNeed = { nav.navigate("${Routes.folder(id)}/need") },
                            onOpenFailed = { nav.navigate("${Routes.folder(id)}/failed") },
                            onOpenChanges = { nav.navigate("${Routes.folder(id)}/changes") },
                            onOpenIgnores = { nav.navigate(Routes.ignores(id)) },
                            onOpenVersions = { nav.navigate("${Routes.folder(id)}/versions") },
                        )
                    }
                }
                composable(Routes.FOLDER_NEED) { entry ->
                    CoreGate(core) { NeedScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.FOLDER_FAILED) { entry ->
                    CoreGate(core) { FailedScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.FOLDER_CHANGES) { entry ->
                    CoreGate(core) { LocalChangesScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = { nav.popBackStack() }) }
                }
                composable(
                    Routes.FOLDER_IGNORES, arguments = listOf(navArgument("first") { type = NavType.BoolType; defaultValue = false }),
                ) { entry ->
                    val args = entry.arguments
                    CoreGate(core) {
                        IgnoresScreen(snapshot, args?.getString("id").orEmpty(), args?.getBoolean("first") == true, vm, onBack = { nav.popBackStack() })
                    }
                }
                composable(Routes.DEVICE) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    CoreGate(core) {
                        DeviceScreen(snapshot, id, vm, onBack = { nav.popBackStack() }, onOpenNeeds = { nav.navigate(Routes.deviceNeeds(id, it)) })
                    }
                }
                composable(Routes.DEVICE_NEEDS) { entry ->
                    val args = entry.arguments
                    CoreGate(core) {
                        RemoteNeedScreen(snapshot, args?.getString("id").orEmpty(), args?.getString("folder").orEmpty(), vm, onBack = { nav.popBackStack() })
                    }
                }
                composable(Routes.THIS_PHONE) {
                    CoreGate(core) { ThisPhoneScreen(snapshot, vm, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.ADD_FOLDER) { entry ->
                    val offer = entry.arguments?.getString("offer")?.ifEmpty { null }
                    val from = entry.arguments?.getString("from")?.ifEmpty { null }
                    CoreGate(core) {
                        // Null until read; a failed read falls back to the app's own defaults.
                        val defaults by produceState<Folder?>(null) { value = vm.defaultFolder() ?: Folder() }
                        val base = defaults
                        if (snapshot.loaded && base != null) {
                            val initial = remember(offer, from) { draftFor(snapshot, offer, from, base) }
                            FolderEditScreen(snapshot, initial, from, pathBase(base), onBack = { nav.popBackStack() }) {
                                vm.addFolder(it, paused = it.ignoresFirst)
                                if (it.ignoresFirst) {
                                    nav.navigate(Routes.ignores(it.id.trim(), first = true)) { popUpTo(Routes.ADD_FOLDER) { inclusive = true } }
                                } else {
                                    nav.popBackStack()
                                }
                            }
                        }
                    }
                }
                composable(Routes.ADD_DEVICE) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val name = entry.arguments?.getString("name").orEmpty()
                    CoreGate(core) {
                        if (snapshot.loaded) {
                            DeviceEditScreen(snapshot, id, name, vm, onBack = { nav.popBackStack() }) { newId, newName, folders ->
                                vm.addDevice(newId, newName, folders)
                                nav.popBackStack()
                            }
                        }
                    }
                }
                composable(Routes.FOLDER_DEFAULTS) {
                    CoreGate(core) {
                        DefaultFolderScreen(vm, onBack = { nav.popBackStack() }, onOpenIgnores = { nav.navigate(Routes.DEFAULT_IGNORES) })
                    }
                }
                composable(Routes.DEFAULT_IGNORES) {
                    CoreGate(core) { IgnoresScreen(snapshot, null, firstSync = false, vm, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.DEVICE_DEFAULTS) {
                    CoreGate(core) { DefaultDeviceScreen(vm, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.RECENT) {
                    CoreGate(core) { RecentChangesScreen(snapshot, vm, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.FOLDER_VERSIONS) { entry ->
                    CoreGate(core) { VersionsScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.IGNORED) {
                    CoreGate(core) { IgnoredScreen(vm, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.WEB_GUI) {
                    (core as? CoreState.Running)?.let { WebGuiScreen(it, onClose = { nav.popBackStack() }) }
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
        title = { Text("Moved from another phone") },
        text = {
            Text(
                "This phone kept the device ID, folders and devices of your old phone, and is checking the folders again. " +
                    "Stop or uninstall Syncthing on the old phone: two phones with the same device ID disturb each other's connections.",
            )
        },
        confirmButton = { TextButton(onClick = vm::dismissMovedNote) { Text("OK") } },
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
                label = { Text(tab.label) },
            )
        }
    }
}
