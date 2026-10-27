package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import eu.stedb.mysyncthing.core.Options
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VpnLock
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
import eu.stedb.mysyncthing.core.PowerSource
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Summary
import eu.stedb.mysyncthing.core.SyncthingService

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    s: Snapshot,
    summary: Summary,
    core: CoreState,
    permissions: PermissionState,
    onOpenWebGui: () -> Unit,
    onOpenIgnored: () -> Unit,
    onOpenFolderDefaults: () -> Unit,
    onOpenDeviceDefaults: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenProxy: () -> Unit,
    onOpenSchedule: () -> Unit,
) {
    val context = LocalContext.current
    val rc by vm.runConditions.collectAsStateWithLifecycle()
    val proxy by vm.proxy.collectAsStateWithLifecycle()
    var powerDialog by rememberSaveable { mutableStateOf(false) }
    val requestNotifications = rememberNotificationRequest(permissions)
    var rename by rememberSaveable { mutableStateOf(false) }
    val running = core is CoreState.Running
    val scope = rememberCoroutineScope()
    var opts by remember { mutableStateOf<Options?>(null) }
    var restartNeeded by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<NetDialog?>(null) }
    LaunchedEffect(running) {
        opts = if (running) vm.options() else null
        restartNeeded = running && vm.restartRequired()
    }
    // Shows the change at once; on failure rereads what the core has.
    val change = { new: Options, label: String, fields: JsonObjectBuilder.() -> Unit ->
        opts = new
        scope.launch {
            if (vm.patchOptions(label, fields)) restartNeeded = vm.restartRequired() else opts = vm.options()
        }
        Unit
    }

    AppScreen(title = "Settings", status = summary) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (restartNeeded && running) {
                ListCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Restart Syncthing to apply the changed settings.", style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(shape = ButtonShape, onClick = {
                            vm.restartCore()
                            restartNeeded = false
                        }) { Text("Restart") }
                    }
                }
            }
            SettingsGroup("This phone") {
                SettingRow("Device name", s.myName.ifEmpty { "Not set" }, Icons.Default.Smartphone) { if (running && s.loaded) rename = true }
            }

            SettingsGroup("Syncing") {
                Toggle("Pause syncing", "Until you resume it here or on the widget", Icons.Default.PauseCircle, rc.pausedByUser) { v ->
                    vm.setRunConditions { it.copy(pausedByUser = v) }
                }
                SettingRow(
                    "Ignored requests", "Devices and folders you tapped Ignore on, to undo it",
                    Icons.Default.Block, onClick = if (running) onOpenIgnored else null,
                )
            }

            SettingsGroup("Sync on") {
                SettingRow("Unmetered Wi-Fi and Ethernet", "Always", Icons.Default.Wifi)
                Toggle(
                    "Metered Wi-Fi and hotspots", "Wi-Fi that Android or you marked as metered, another phone's hotspot, tethering",
                    Icons.Default.NetworkCheck, rc.meteredWifi,
                ) { v -> vm.setRunConditions { it.copy(meteredWifi = v) } }
                Toggle("Mobile data", "Can cost money on a limited plan", Icons.Default.SignalCellularAlt, rc.mobileData) { v ->
                    vm.setRunConditions { it.copy(mobileData = v) }
                }
                if (rc.mobileData) {
                    Toggle("Mobile data while roaming", "Abroad or on another network, where data can cost extra", Icons.Default.TravelExplore, rc.roaming) { v ->
                        vm.setRunConditions { it.copy(roaming = v) }
                    }
                }
            }

            SettingsGroup("Time") {
                SettingRow("Sync times", scheduleText(rc), Icons.Default.Schedule, onClick = onOpenSchedule)
            }

            SettingsGroup("Power") {
                SettingRow("Sync when", powerText(rc.power), Icons.Default.BatteryChargingFull) { powerDialog = true }
                Toggle("Pause in battery saver", "Syncing waits while battery saver is on", Icons.Default.BatterySaver, rc.respectBatterySaver) { v ->
                    vm.setRunConditions { it.copy(respectBatterySaver = v) }
                }
            }

            SettingsGroup("New folders and devices") {
                SettingRow(
                    "Default settings for new folders", "Type, versioning, ignore patterns and more",
                    Icons.Default.CreateNewFolder, onClick = if (running) onOpenFolderDefaults else null,
                )
                SettingRow(
                    "Default settings for new devices", "Auto-accept, addresses, limits and more",
                    Icons.Default.DevicesOther, onClick = if (running) onOpenDeviceDefaults else null,
                )
            }

            // The proxy is the app's setting, so it can be changed while the core is stopped or failed.
            SettingsGroup("Network") {
                opts?.let { o ->
                    SettingRow("Download limit", limitText(o.maxRecvKbps), Icons.Default.Download) { dialog = NetDialog.Recv }
                    SettingRow("Upload limit", limitText(o.maxSendKbps), Icons.Default.Upload) { dialog = NetDialog.Send }
                    Toggle(
                        "Also limit on local network", if (o.limitBandwidthInLan) "Limits apply everywhere" else "Off: transfers on the local network run at full speed",
                        Icons.Default.Lan, o.limitBandwidthInLan,
                    ) { v -> change(o.copy(limitBandwidthInLan = v), "Limit") { put("limitBandwidthInLan", v) } }
                }
                SettingRow("Proxy", proxy?.let(::proxyText), Icons.Default.VpnLock, onClick = onOpenProxy)
            }
            opts?.let { o ->
                SettingsGroup("Finding devices") {
                    Toggle("Local network", "Find devices on the same Wi-Fi", Icons.Default.Router, o.localAnnounceEnabled) { v ->
                        change(o.copy(localAnnounceEnabled = v), "Local discovery") { put("localAnnounceEnabled", v) }
                    }
                    Toggle(
                        "Internet discovery", "Look devices up through Syncthing's discovery servers. They learn this phone's IP address.",
                        Icons.Default.Public, o.globalAnnounceEnabled,
                    ) { v -> change(o.copy(globalAnnounceEnabled = v), "Internet discovery") { put("globalAnnounceEnabled", v) } }
                    Toggle(
                        "Relays", "Connect through a relay when a direct connection fails. Still end-to-end encrypted.",
                        Icons.Default.SyncAlt, o.relaysEnabled,
                    ) { v -> change(o.copy(relaysEnabled = v), "Relays") { put("relaysEnabled", v) } }
                    Toggle("Router port opening", "Ask the router to open a port (UPnP, NAT-PMP)", Icons.Default.SettingsEthernet, o.natEnabled) { v ->
                        change(o.copy(natEnabled = v), "Port opening") { put("natEnabled", v) }
                    }
                }
            }

            SettingsGroup("System") {
                Toggle("Start at boot", "Sync starts when the phone starts", Icons.Default.RestartAlt, rc.startOnBoot) { v ->
                    vm.setRunConditions { it.copy(startOnBoot = v) }
                }
                when (core) {
                    is CoreState.Stopped, is CoreState.Failed ->
                        SettingRow("Start Syncthing", null, Icons.Default.PowerSettingsNew) { SyncthingService.start(context) }
                    else -> SettingRow("Stop Syncthing", "Until you start it again or the phone restarts", Icons.Default.PowerSettingsNew) {
                        SyncthingService.stopByUser(context)
                    }
                }
                if (running) {
                    SettingRow("Restart Syncthing", "Applies settings that need a restart", Icons.Default.Refresh) {
                        vm.restartCore()
                        restartNeeded = false
                    }
                }
                SettingRow(
                    "Export a backup", "Folders, devices, this phone's identity and sync settings, as a zip file",
                    Icons.Default.SaveAlt, onClick = onOpenExport,
                )
                SettingRow(
                    "Import a backup", "From this app's export or a Syncthing-Fork backup",
                    Icons.Default.FileOpen, onClick = onOpenImport,
                )
            }

            SettingsGroup("Permissions") {
                PermissionRows(permissions, requestNotifications)
            }

            SettingsGroup("Advanced") {
                var usage by remember { mutableStateOf<UsageConsent?>(null) }
                LaunchedEffect(running) { usage = vm.usageConsent() }
                usage?.let { u ->
                    SwitchRow(
                        "Anonymous usage reporting", "Helps Syncthing's developers; statistics at data.syncthing.net",
                        u.enabled, Icons.Default.QueryStats,
                    ) { v ->
                        vm.setUsageReporting(v)
                        usage = u.copy(enabled = v)
                    }
                }
                opts?.let { o ->
                    SettingRow("Listen addresses", listText(o.listenAddresses, "Default, port 22000"), Icons.Default.SettingsInputAntenna) {
                        dialog = NetDialog.Listen
                    }
                    SettingRow("Discovery servers", listText(o.globalAnnounceServers, "Syncthing's public servers"), Icons.Default.Dns) {
                        dialog = NetDialog.Servers
                    }
                }
                SettingRow(
                    "Web GUI", "Syncthing's full settings, for what this app does not show",
                    Icons.Default.Language, onClick = if (running) onOpenWebGui else null,
                )
                SettingRow("About", "Versions, licences and source code", Icons.Default.Info, onClick = onOpenAbout)
            }
        }
    }

    val o = opts
    val close = { dialog = null }
    if (o != null) when (dialog) {
        NetDialog.Recv -> SpeedLimitDialog("Download limit", o.maxRecvKbps, onDismiss = close) { kib ->
            close(); change(o.copy(maxRecvKbps = kib), "Download limit") { put("maxRecvKbps", kib) }
        }
        NetDialog.Send -> SpeedLimitDialog("Upload limit", o.maxSendKbps, onDismiss = close) { kib ->
            close(); change(o.copy(maxSendKbps = kib), "Upload limit") { put("maxSendKbps", kib) }
        }
        NetDialog.Listen -> ListEditDialog(
            "Listen addresses", o.listenAddresses, token = "default", tokenTitle = "Default",
            tokenHint = "TCP and QUIC on port 22000, plus relays",
            addHint = "For another port, add tcp://0.0.0.0:22001 and quic://0.0.0.0:22001 and untick Default.",
            valid = ::isListenAddress, onDismiss = close,
        ) { list -> close(); change(o.copy(listenAddresses = list), "Listen addresses") { putStrings("listenAddresses", list) } }
        NetDialog.Servers -> ListEditDialog(
            "Discovery servers", o.globalAnnounceServers, token = "default", tokenTitle = "Syncthing's public servers",
            tokenHint = "Used by internet discovery",
            addHint = "Your own server, for example https://discovery.example.com/ (https only)",
            valid = ::isDiscoveryServer, onDismiss = close,
        ) { list -> close(); change(o.copy(globalAnnounceServers = list), "Discovery servers") { putStrings("globalAnnounceServers", list) } }
        null -> {}
    }

    if (powerDialog) {
        ChoiceDialog(
            "Sync when", PowerSource.entries.map { it to powerText(it) }, rc.power, describe = ::powerHint,
            onDismiss = { powerDialog = false },
        ) { p -> powerDialog = false; vm.setRunConditions { it.copy(power = p) } }
    }

    if (rename) {
        TextDialog("Device name", s.myName, "Name", supporting = "Other devices see this name", onDismiss = { rename = false }) {
            vm.renameDevice(s.myId, it); rename = false
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String, icon: ImageVector, value: Boolean, onChange: (Boolean) -> Unit) =
    SwitchRow(title, hint, value, icon, onChange)

private enum class NetDialog { Recv, Send, Listen, Servers }

/** [defaultText] for the default token alone, else the entries as stored. */
private fun listText(list: List<String>, defaultText: String): String =
    if (list.isEmpty() || list == listOf("default")) defaultText else list.joinToString(", ")

private fun JsonObjectBuilder.putStrings(key: String, list: List<String>) {
    putJsonArray(key) { list.forEach { add(JsonPrimitive(it)) } }
}

fun powerText(p: PowerSource): String = when (p) {
    PowerSource.Any -> "Charging or on battery"
    PowerSource.ChargingOnly -> "Only while charging"
    PowerSource.BatteryOnly -> "Only on battery"
}

private fun powerHint(p: PowerSource): String? = when (p) {
    PowerSource.Any -> null
    PowerSource.ChargingOnly -> "Pauses when unplugged"
    PowerSource.BatteryOnly -> "Pauses while plugged in"
}
