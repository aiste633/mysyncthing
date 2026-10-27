package eu.stedb.mysyncthing.ui

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.Options
import eu.stedb.mysyncthing.core.PowerSource
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Summary
import eu.stedb.mysyncthing.core.SyncthingService
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.text
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
    val change = { new: Options, label: Int, fields: JsonObjectBuilder.() -> Unit ->
        opts = new
        scope.launch {
            if (vm.patchOptions(label, fields)) restartNeeded = vm.restartRequired() else opts = vm.options()
        }
        Unit
    }

    AppScreen(title = stringResource(R.string.tab_settings), status = summary) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (restartNeeded && running) {
                ListCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_restart_needed), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(shape = ButtonShape, onClick = {
                            vm.restartCore()
                            restartNeeded = false
                        }) { Text(stringResource(R.string.action_restart)) }
                    }
                }
            }
            SettingsGroup(stringResource(R.string.this_phone)) {
                SettingRow(stringResource(R.string.settings_device_name), s.myName.ifEmpty { stringResource(R.string.settings_not_set) }, Icons.Default.Smartphone) { if (running && s.loaded) rename = true }
            }

            SettingsGroup(stringResource(R.string.settings_group_syncing)) {
                Toggle(stringResource(R.string.settings_pause), stringResource(R.string.settings_pause_hint), Icons.Default.PauseCircle, rc.pausedByUser) { v ->
                    vm.setRunConditions { it.copy(pausedByUser = v) }
                }
                SettingRow(
                    stringResource(R.string.settings_ignored), stringResource(R.string.settings_ignored_hint),
                    Icons.Default.Block, onClick = if (running) onOpenIgnored else null,
                )
            }

            SettingsGroup(stringResource(R.string.settings_group_sync_on)) {
                SettingRow(stringResource(R.string.settings_unmetered), stringResource(R.string.settings_always), Icons.Default.Wifi)
                Toggle(
                    stringResource(R.string.settings_metered), stringResource(R.string.settings_metered_hint),
                    Icons.Default.NetworkCheck, rc.meteredWifi,
                ) { v -> vm.setRunConditions { it.copy(meteredWifi = v) } }
                Toggle(stringResource(R.string.settings_mobile_data), stringResource(R.string.settings_mobile_data_hint), Icons.Default.SignalCellularAlt, rc.mobileData) { v ->
                    vm.setRunConditions { it.copy(mobileData = v) }
                }
                if (rc.mobileData) {
                    Toggle(stringResource(R.string.settings_roaming), stringResource(R.string.settings_roaming_hint), Icons.Default.TravelExplore, rc.roaming) { v ->
                        vm.setRunConditions { it.copy(roaming = v) }
                    }
                }
            }

            SettingsGroup(stringResource(R.string.settings_group_time)) {
                SettingRow(stringResource(R.string.sync_times), scheduleText(rc).asString(), Icons.Default.Schedule, onClick = onOpenSchedule)
            }

            SettingsGroup(stringResource(R.string.settings_group_power)) {
                SettingRow(stringResource(R.string.settings_power), powerText(rc.power).asString(), Icons.Default.BatteryChargingFull) { powerDialog = true }
                Toggle(stringResource(R.string.settings_battery_saver), stringResource(R.string.settings_battery_saver_hint), Icons.Default.BatterySaver, rc.respectBatterySaver) { v ->
                    vm.setRunConditions { it.copy(respectBatterySaver = v) }
                }
            }

            SettingsGroup(stringResource(R.string.settings_group_defaults)) {
                SettingRow(
                    stringResource(R.string.defaults_folders), stringResource(R.string.defaults_folders_hint),
                    Icons.Default.CreateNewFolder, onClick = if (running) onOpenFolderDefaults else null,
                )
                SettingRow(
                    stringResource(R.string.defaults_devices), stringResource(R.string.defaults_devices_hint),
                    Icons.Default.DevicesOther, onClick = if (running) onOpenDeviceDefaults else null,
                )
            }

            // The proxy is the app's setting, so it can be changed while the core is stopped or failed.
            SettingsGroup(stringResource(R.string.settings_group_network)) {
                opts?.let { o ->
                    SettingRow(stringResource(R.string.download_limit), limitText(o.maxRecvKbps).asString(), Icons.Default.Download) { dialog = NetDialog.Recv }
                    SettingRow(stringResource(R.string.upload_limit), limitText(o.maxSendKbps).asString(), Icons.Default.Upload) { dialog = NetDialog.Send }
                    Toggle(
                        stringResource(R.string.settings_limit_lan), if (o.limitBandwidthInLan) stringResource(R.string.settings_limit_lan_on) else stringResource(R.string.settings_limit_lan_off),
                        Icons.Default.Lan, o.limitBandwidthInLan,
                    ) { v -> change(o.copy(limitBandwidthInLan = v), R.string.op_limit) { put("limitBandwidthInLan", v) } }
                }
                SettingRow(stringResource(R.string.proxy), proxy?.let(::proxyText)?.asString(), Icons.Default.VpnLock, onClick = onOpenProxy)
            }
            opts?.let { o ->
                SettingsGroup(stringResource(R.string.settings_group_discovery)) {
                    Toggle(stringResource(R.string.settings_local_discovery), stringResource(R.string.settings_local_discovery_hint), Icons.Default.Router, o.localAnnounceEnabled) { v ->
                        change(o.copy(localAnnounceEnabled = v), R.string.op_local_discovery) { put("localAnnounceEnabled", v) }
                    }
                    Toggle(
                        stringResource(R.string.settings_global_discovery), stringResource(R.string.settings_global_discovery_hint),
                        Icons.Default.Public, o.globalAnnounceEnabled,
                    ) { v -> change(o.copy(globalAnnounceEnabled = v), R.string.op_internet_discovery) { put("globalAnnounceEnabled", v) } }
                    Toggle(
                        stringResource(R.string.settings_relays), stringResource(R.string.settings_relays_hint),
                        Icons.Default.SyncAlt, o.relaysEnabled,
                    ) { v -> change(o.copy(relaysEnabled = v), R.string.op_relays) { put("relaysEnabled", v) } }
                    Toggle(stringResource(R.string.settings_nat), stringResource(R.string.settings_nat_hint), Icons.Default.SettingsEthernet, o.natEnabled) { v ->
                        change(o.copy(natEnabled = v), R.string.op_port_opening) { put("natEnabled", v) }
                    }
                }
            }

            SettingsGroup(stringResource(R.string.settings_group_system)) {
                SettingRow(stringResource(R.string.settings_language), appLanguage(context), Icons.Default.Translate) {
                    context.startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }
                Toggle(stringResource(R.string.settings_boot), stringResource(R.string.settings_boot_hint), Icons.Default.RestartAlt, rc.startOnBoot) { v ->
                    vm.setRunConditions { it.copy(startOnBoot = v) }
                }
                when (core) {
                    is CoreState.Stopped, is CoreState.Failed ->
                        SettingRow(stringResource(R.string.settings_start), null, Icons.Default.PowerSettingsNew) { SyncthingService.start(context) }
                    else -> SettingRow(stringResource(R.string.settings_stop), stringResource(R.string.settings_stop_hint), Icons.Default.PowerSettingsNew) {
                        SyncthingService.stopByUser(context)
                    }
                }
                if (running) {
                    SettingRow(stringResource(R.string.settings_restart), stringResource(R.string.settings_restart_hint), Icons.Default.Refresh) {
                        vm.restartCore()
                        restartNeeded = false
                    }
                }
                SettingRow(
                    stringResource(R.string.settings_export), stringResource(R.string.settings_export_hint),
                    Icons.Default.SaveAlt, onClick = onOpenExport,
                )
                SettingRow(
                    stringResource(R.string.settings_import), stringResource(R.string.settings_import_hint),
                    Icons.Default.FileOpen, onClick = onOpenImport,
                )
            }

            SettingsGroup(stringResource(R.string.settings_group_permissions)) {
                PermissionRows(permissions, requestNotifications)
            }

            SettingsGroup(stringResource(R.string.settings_group_advanced)) {
                var usage by remember { mutableStateOf<UsageConsent?>(null) }
                LaunchedEffect(running) { usage = vm.usageConsent() }
                usage?.let { u ->
                    SwitchRow(
                        stringResource(R.string.settings_usage), stringResource(R.string.settings_usage_hint),
                        u.enabled, Icons.Default.QueryStats,
                    ) { v ->
                        vm.setUsageReporting(v)
                        usage = u.copy(enabled = v)
                    }
                }
                opts?.let { o ->
                    SettingRow(stringResource(R.string.listen_addresses), listText(o.listenAddresses, stringResource(R.string.settings_listen_default)), Icons.Default.SettingsInputAntenna) {
                        dialog = NetDialog.Listen
                    }
                    SettingRow(stringResource(R.string.discovery_servers), listText(o.globalAnnounceServers, stringResource(R.string.settings_servers_default)), Icons.Default.Dns) {
                        dialog = NetDialog.Servers
                    }
                }
                SettingRow(
                    stringResource(R.string.settings_web_gui), stringResource(R.string.settings_web_gui_hint),
                    Icons.Default.Language, onClick = if (running) onOpenWebGui else null,
                )
                SettingRow(stringResource(R.string.about), stringResource(R.string.settings_about_hint), Icons.Default.Info, onClick = onOpenAbout)
            }
        }
    }

    val o = opts
    val close = { dialog = null }
    if (o != null) when (dialog) {
        NetDialog.Recv -> SpeedLimitDialog(stringResource(R.string.download_limit), o.maxRecvKbps, onDismiss = close) { kib ->
            close(); change(o.copy(maxRecvKbps = kib), R.string.op_download_limit) { put("maxRecvKbps", kib) }
        }
        NetDialog.Send -> SpeedLimitDialog(stringResource(R.string.upload_limit), o.maxSendKbps, onDismiss = close) { kib ->
            close(); change(o.copy(maxSendKbps = kib), R.string.op_upload_limit) { put("maxSendKbps", kib) }
        }
        NetDialog.Listen -> ListEditDialog(
            stringResource(R.string.listen_addresses), o.listenAddresses, token = "default", tokenTitle = stringResource(R.string.hint_default),
            tokenHint = stringResource(R.string.listen_default_hint),
            addHint = stringResource(R.string.listen_add_hint),
            valid = ::isListenAddress, onDismiss = close,
        ) { list -> close(); change(o.copy(listenAddresses = list), R.string.op_listen_addresses) { putStrings("listenAddresses", list) } }
        NetDialog.Servers -> ListEditDialog(
            stringResource(R.string.discovery_servers), o.globalAnnounceServers, token = "default", tokenTitle = stringResource(R.string.settings_servers_default),
            tokenHint = stringResource(R.string.servers_default_hint),
            addHint = stringResource(R.string.servers_add_hint),
            valid = ::isDiscoveryServer, onDismiss = close,
        ) { list -> close(); change(o.copy(globalAnnounceServers = list), R.string.op_discovery_servers) { putStrings("globalAnnounceServers", list) } }
        null -> {}
    }

    if (powerDialog) {
        ChoiceDialog(
            stringResource(R.string.settings_power), PowerSource.entries.map { it to powerText(it) }, rc.power, describe = ::powerHint,
            onDismiss = { powerDialog = false },
        ) { p -> powerDialog = false; vm.setRunConditions { it.copy(power = p) } }
    }

    if (rename) {
        TextDialog(stringResource(R.string.settings_device_name), s.myName, stringResource(R.string.name), supporting = stringResource(R.string.settings_device_name_hint), onDismiss = { rename = false }) {
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

fun powerText(p: PowerSource): UiText = when (p) {
    PowerSource.Any -> text(R.string.power_any)
    PowerSource.ChargingOnly -> text(R.string.power_charging)
    PowerSource.BatteryOnly -> text(R.string.power_battery)
}

private fun powerHint(p: PowerSource): UiText? = when (p) {
    PowerSource.Any -> null
    PowerSource.ChargingOnly -> text(R.string.power_charging_hint)
    PowerSource.BatteryOnly -> text(R.string.power_battery_hint)
}

/**
 * The language chosen for this app on Android's per-app language page, in that
 * language ("Italiano"), or that it follows the phone. Choosing one recreates
 * the activity, so reading it once per composition is enough.
 */
@Composable
private fun appLanguage(context: Context): String {
    val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
    if (locales.isEmpty) return stringResource(R.string.settings_language_phone)
    val l = locales[0]
    return l.getDisplayName(l).replaceFirstChar { it.titlecase(l) }
}
