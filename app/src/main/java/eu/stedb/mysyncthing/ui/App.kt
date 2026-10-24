package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.SyncthingService

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Folders("folders", "Folders", Icons.Default.Folder),
    Devices("devices", "Devices", Icons.Default.Devices),
    Settings("settings", "Settings", Icons.Default.Settings),
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)) {
        val permissions = rememberPermissionState()
        if (!permissions.storage) {
            SetupScreen(permissions)
            return@MaterialTheme
        }
        LaunchedEffect(Unit) { SyncthingService.start(context) }

        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
        val core by vm.core.collectAsStateWithLifecycle()
        val snapshot by vm.snapshot.collectAsStateWithLifecycle()
        val current by nav.currentBackStackEntryAsState()
        val route = current?.destination?.route

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (route != WEB_GUI) NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Tab.Folders.route, modifier = Modifier.padding(padding)) {
                composable(Tab.Folders.route) { CoreGate(core) { FoldersScreen(snapshot, vm) } }
                composable(Tab.Devices.route) { CoreGate(core) { DevicesScreen(snapshot, vm) } }
                composable(Tab.Settings.route) {
                    SettingsScreen(vm, core, permissions, onOpenWebGui = { nav.navigate(WEB_GUI) })
                }
                composable(WEB_GUI) {
                    (core as? CoreState.Running)?.let { WebGuiScreen(it, onClose = { nav.popBackStack() }) }
                }
            }
        }
    }
}

private const val WEB_GUI = "webgui"
