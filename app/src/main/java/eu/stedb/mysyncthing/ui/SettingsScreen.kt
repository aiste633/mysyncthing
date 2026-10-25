package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Summary
import eu.stedb.mysyncthing.core.SyncthingCore
import eu.stedb.mysyncthing.core.SyncthingService

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    s: Snapshot,
    summary: Summary,
    core: CoreState,
    permissions: PermissionState,
    onOpenWebGui: () -> Unit,
) {
    val context = LocalContext.current
    val rc by vm.runConditions.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationRequest(permissions)
    var rename by rememberSaveable { mutableStateOf(false) }
    val running = core is CoreState.Running

    AppScreen(title = "Settings", actions = { StatusChip(summary) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SettingsGroup("This phone") {
                SettingRow("Device name", s.myName.ifEmpty { "Not set" }, Icons.Default.Smartphone) { if (running && s.loaded) rename = true }
            }

            SettingsGroup("Syncing") {
                Toggle("Pause syncing", "Until you resume it here or on the widget", Icons.Default.PauseCircle, rc.pausedByUser) { v ->
                    vm.setRunConditions { it.copy(pausedByUser = v) }
                }
            }

            SettingsGroup("Sync only when") {
                Toggle("On Wi-Fi or Ethernet", "Pause on mobile data", Icons.Default.Wifi, rc.wifiOnly) { v ->
                    vm.setRunConditions { it.copy(wifiOnly = v) }
                }
                Toggle("On unmetered networks", "Pause on hotspots and metered Wi-Fi", Icons.Default.NetworkCheck, rc.unmeteredOnly) { v ->
                    vm.setRunConditions { it.copy(unmeteredOnly = v) }
                }
                Toggle("Charging", "Pause while on battery", Icons.Default.BatteryChargingFull, rc.chargingOnly) { v ->
                    vm.setRunConditions { it.copy(chargingOnly = v) }
                }
                Toggle("Battery saver is off", "Pause while battery saver is on", Icons.Default.BatterySaver, rc.respectBatterySaver) { v ->
                    vm.setRunConditions { it.copy(respectBatterySaver = v) }
                }
            }

            SettingsGroup("System") {
                Toggle("Start at boot", "Sync starts when the phone starts", Icons.Default.RestartAlt, rc.startOnBoot) { v ->
                    vm.setRunConditions { it.copy(startOnBoot = v) }
                }
                if (!permissions.notifications) {
                    SettingRow("Allow notifications", "Shows sync status and new requests", Icons.Default.Notifications, onClick = requestNotifications)
                }
                if (!permissions.batteryExempt) {
                    SettingRow(
                        "Disable battery optimisation", "Keeps sync running reliably in the background",
                        Icons.Default.BatteryAlert, onClick = permissions::requestBatteryExemption,
                    )
                }
                when (core) {
                    is CoreState.Stopped, is CoreState.Failed ->
                        SettingRow("Start Syncthing", null, Icons.Default.PowerSettingsNew) { SyncthingService.start(context) }
                    else -> SettingRow("Stop Syncthing", "Until you start it again or the phone restarts", Icons.Default.PowerSettingsNew) {
                        SyncthingService.stop(context)
                    }
                }
            }

            SettingsGroup("Advanced") {
                var usage by remember { mutableStateOf<UsageConsent?>(null) }
                LaunchedEffect(running) { usage = vm.usageConsent() }
                usage?.let { u ->
                    SettingRow(
                        "Anonymous usage reporting", "Helps Syncthing's developers; statistics at data.syncthing.net",
                        Icons.Default.QueryStats, trailing = { Switch(u.enabled, onCheckedChange = null) },
                    ) {
                        vm.setUsageReporting(!u.enabled)
                        usage = u.copy(enabled = !u.enabled)
                    }
                }
                SettingRow(
                    "Web GUI", "Syncthing's full settings, for what this app does not show",
                    Icons.Default.Language, onClick = if (running) onOpenWebGui else null,
                )
                SettingRow("Syncthing core", SyncthingCore.version, Icons.Default.Info)
            }
        }
    }

    if (rename) {
        TextDialog("Device name", s.myName, "Name", supporting = "Other devices see this name", onDismiss = { rename = false }) {
            vm.renameDevice(s.myId, it); rename = false
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String, icon: ImageVector, value: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(title, hint, icon, trailing = { Switch(value, onCheckedChange = null) }) { onChange(!value) }
