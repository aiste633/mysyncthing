package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.SyncthingCore
import eu.stedb.mysyncthing.core.SyncthingService

@Composable
fun SettingsScreen(vm: MainViewModel, core: CoreState, permissions: PermissionState, onOpenWebGui: () -> Unit) {
    val context = LocalContext.current
    val rc by vm.runConditions.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationRequest(permissions)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("Run conditions")
        Toggle("Wi-Fi or Ethernet only", rc.wifiOnly) { v -> vm.setRunConditions { it.copy(wifiOnly = v) } }
        Toggle("Unmetered networks only", rc.unmeteredOnly) { v -> vm.setRunConditions { it.copy(unmeteredOnly = v) } }
        Toggle("Only while charging", rc.chargingOnly) { v -> vm.setRunConditions { it.copy(chargingOnly = v) } }
        Toggle("Pause in battery saver", rc.respectBatterySaver) { v -> vm.setRunConditions { it.copy(respectBatterySaver = v) } }
        Toggle("Start at boot", rc.startOnBoot) { v -> vm.setRunConditions { it.copy(startOnBoot = v) } }
        HorizontalDivider()

        Section("System")
        if (!permissions.notifications) Action("Allow notifications", requestNotifications)
        if (!permissions.batteryExempt) Action("Disable battery optimisation", permissions::requestBatteryExemption)
        if (core is CoreState.Running) Action("Open web GUI (advanced)", onOpenWebGui)
        when (core) {
            is CoreState.Stopped, is CoreState.Failed -> Action("Start Syncthing") { SyncthingService.start(context) }
            else -> Action("Stop Syncthing") { SyncthingService.stop(context) }
        }
        HorizontalDivider()
        ListItem(headlineContent = { Text("Syncthing core") }, supportingContent = { Text(SyncthingCore.version) })
    }
}

@Composable
private fun Section(title: String) = ListItem(headlineContent = { Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall) })

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(value, onChange) })

@Composable
private fun Action(label: String, onClick: () -> Unit) =
    ListItem(headlineContent = { TextButton(onClick = onClick) { Text(label) } })

