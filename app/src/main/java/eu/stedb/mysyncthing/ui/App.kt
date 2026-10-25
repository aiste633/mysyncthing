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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.SyncthingService

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
}

private object Routes {
    const val FOLDER = "folder/{id}"
    const val DEVICE = "device/{id}"
    const val ADD_FOLDER = "add-folder?offer={offer}&from={from}"
    const val WEB_GUI = "webgui"

    fun folder(id: String) = "folder/${Uri.encode(id)}"
    fun device(id: String) = "device/${Uri.encode(id)}"
    fun addFolder(offer: String? = null, from: String? = null) =
        "add-folder?offer=${Uri.encode(offer.orEmpty())}&from=${Uri.encode(from.orEmpty())}"
}

@Composable
fun App(target: LaunchTarget? = null, onTargetShown: () -> Unit = {}, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    AppTheme {
        val permissions = rememberPermissionState()
        if (!permissions.storage) {
            SetupScreen(permissions)
            return@AppTheme
        }
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
        var reviewDevice by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(target) {
            when (target) {
                is LaunchTarget.Tab -> Tab.entries.find { it.route == target.route }?.let { nav.navigate(it.route) { launchSingleTop = true } }
                is LaunchTarget.ReviewFolder -> nav.navigate(Routes.addFolder(target.folder, target.device))
                is LaunchTarget.ReviewDevice -> {
                    reviewDevice = target.device
                    nav.navigate(Tab.Devices.route) { launchSingleTop = true }
                }
                null -> return@LaunchedEffect
            }
            onTargetShown()
        }

        UsageReportingPrompt(core, vm)

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
                        )
                    }
                }
                composable(Tab.Devices.route) {
                    CoreGate(core) {
                        DevicesScreen(
                            snapshot, summary, vm, onOpen = { nav.navigate(Routes.device(it)) },
                            review = reviewDevice, onReviewShown = { reviewDevice = null },
                        )
                    }
                }
                composable(Tab.Settings.route) {
                    SettingsScreen(vm, snapshot, summary, core, permissions, onOpenWebGui = { nav.navigate(Routes.WEB_GUI) })
                }
                composable(Routes.FOLDER) { entry ->
                    CoreGate(core) { FolderScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.DEVICE) { entry ->
                    CoreGate(core) { DeviceScreen(snapshot, entry.arguments?.getString("id").orEmpty(), vm, onBack = { nav.popBackStack() }) }
                }
                composable(Routes.ADD_FOLDER) { entry ->
                    val offer = entry.arguments?.getString("offer")?.ifEmpty { null }
                    val from = entry.arguments?.getString("from")?.ifEmpty { null }
                    CoreGate(core) {
                        if (snapshot.loaded) {
                            val initial = remember(offer, from) { draftFor(snapshot, offer, from) }
                            FolderEditScreen(snapshot, initial, from, onBack = { nav.popBackStack() }) {
                                vm.addFolder(it)
                                nav.popBackStack()
                            }
                        }
                    }
                }
                composable(Routes.WEB_GUI) {
                    (core as? CoreState.Running)?.let { WebGuiScreen(it, onClose = { nav.popBackStack() }) }
                }
            }
        }
    }
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
