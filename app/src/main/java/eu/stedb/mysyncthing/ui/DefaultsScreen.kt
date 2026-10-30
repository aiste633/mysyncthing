package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Versioning
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private enum class DefaultsDialog { Type, Rescan, Order, DiskFree, Addresses, Recv, Send, Compression, Connections }

/**
 * What every new folder starts with: the folder screen's settings without
 * status, sharing and Remove. Folders accepted automatically use them too.
 */
@Composable
fun DefaultFolderScreen(vm: MainViewModel, onBack: () -> Unit, onOpenIgnores: () -> Unit) {
    // Kept in the view model, so a recreated screen has them, and the dialog open on them, on its first frame.
    val f by vm.defaultFolder.value.collectAsStateWithLifecycle()
    val ignores by vm.defaultIgnores.value.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        launch { vm.defaultFolder.load() }
        vm.defaultIgnores.load()
    }
    var dialog by rememberSaveable { mutableStateOf<DefaultsDialog?>(null) }

    AppScreen(title = stringResource(R.string.defaults_folders), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            val d = f
            if (d == null) {
                Intro(stringResource(R.string.loading))
                return@Column
            }
            Intro(stringResource(R.string.defaults_folders_intro))
            SettingsGroup(stringResource(R.string.group_general)) {
                SettingRow(stringResource(R.string.type), folderTypeText(d.type).asString()) { dialog = DefaultsDialog.Type }
                SettingRow(stringResource(R.string.defaults_location), stringResource(R.string.defaults_location_value, displayPath(d.path).asString()))
            }
            SettingsGroup(stringResource(R.string.file_versioning)) {
                VersioningEditor(d.versioning) { v -> vm.changeDefaultFolder(d.copy(versioning = v), R.string.op_versioning) { putVersioning(v) } }
            }
            SettingsGroup(stringResource(R.string.settings_group_advanced)) {
                SwitchRow(stringResource(R.string.folder_watch), stringResource(R.string.folder_watch_hint), d.fsWatcherEnabled) { v ->
                    vm.changeDefaultFolder(d.copy(fsWatcherEnabled = v), R.string.op_watch) { put("fsWatcherEnabled", v) }
                }
                SettingRow(stringResource(R.string.full_rescan), rescanText(d.rescanIntervalS).asString()) { dialog = DefaultsDialog.Rescan }
                SettingRow(stringResource(R.string.download_order), orderLabel(d.order).asString()) { dialog = DefaultsDialog.Order }
                SettingRow(stringResource(R.string.keep_free), diskSizeText(d.minDiskFree).asString()) { dialog = DefaultsDialog.DiskFree }
                SettingRow(
                    stringResource(R.string.ignore_patterns), ignoresText(ignores).asString(),
                    onClick = onOpenIgnores,
                )
            }
        }
    }

    val d = f ?: return
    val close = { dialog = null }
    when (dialog) {
        DefaultsDialog.Type -> ChoiceDialog(stringResource(R.string.folder_type), FOLDER_TYPES, d.type, ::folderTypeHint, onDismiss = close) { t ->
            close(); vm.changeDefaultFolder(d.copy(type = t), R.string.op_type) { put("type", t) }
        }
        DefaultsDialog.Rescan -> ChoiceDialog(stringResource(R.string.full_rescan), rescanChoices(d.rescanIntervalS), d.rescanIntervalS, ::rescanHint, onDismiss = close) { n ->
            close(); vm.changeDefaultFolder(d.copy(rescanIntervalS = n), R.string.op_full_rescan) { put("rescanIntervalS", n) }
        }
        DefaultsDialog.Order -> ChoiceDialog(stringResource(R.string.download_order), PULL_ORDERS, d.order, onDismiss = close) { o ->
            close(); vm.changeDefaultFolder(d.copy(order = o), R.string.op_download_order) { put("order", o) }
        }
        DefaultsDialog.DiskFree -> DiskFreeDialog(d.minDiskFree, INTERNAL_STORAGE, onDismiss = close) { size ->
            close()
            vm.changeDefaultFolder(d.copy(minDiskFree = size), R.string.op_keep_free) { putJsonObject("minDiskFree") { put("value", size.value); put("unit", size.unit) } }
        }
        else -> {}
    }
}

/** What every new device starts with: the device screen's settings without status, ID and Remove. */
@Composable
fun DefaultDeviceScreen(vm: MainViewModel, onBack: () -> Unit) {
    val dev by vm.defaultDevice.value.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.defaultDevice.load() }
    var dialog by rememberSaveable { mutableStateOf<DefaultsDialog?>(null) }

    AppScreen(title = stringResource(R.string.defaults_devices), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            val d = dev
            if (d == null) {
                Intro(stringResource(R.string.loading))
                return@Column
            }
            Intro(stringResource(R.string.defaults_devices_intro))
            SettingsGroup(stringResource(R.string.group_trust)) {
                SwitchRow(stringResource(R.string.device_introducer), stringResource(R.string.device_introducer_hint), d.introducer) { v ->
                    vm.changeDefaultDevice(d.copy(introducer = v), R.string.op_introducer) { put("introducer", v) }
                }
                SwitchRow(stringResource(R.string.device_auto_accept), stringResource(R.string.device_auto_accept_hint), d.autoAcceptFolders) { v ->
                    vm.changeDefaultDevice(d.copy(autoAcceptFolders = v), R.string.op_accept_folders) { put("autoAcceptFolders", v) }
                }
            }
            SettingsGroup(stringResource(R.string.settings_group_advanced)) {
                SettingRow(stringResource(R.string.device_addresses), addressesText(d.addresses)) { dialog = DefaultsDialog.Addresses }
                SettingRow(stringResource(R.string.download_limit), limitText(d.maxRecvKbps).asString()) { dialog = DefaultsDialog.Recv }
                SettingRow(stringResource(R.string.upload_limit), limitText(d.maxSendKbps).asString()) { dialog = DefaultsDialog.Send }
                SettingRow(stringResource(R.string.compression), compressionLabel(d.compression).asString()) { dialog = DefaultsDialog.Compression }
                SettingRow(stringResource(R.string.connections), connectionsText(d.numConnections).asString()) { dialog = DefaultsDialog.Connections }
            }
        }
    }

    val d = dev ?: return
    val close = { dialog = null }
    when (dialog) {
        DefaultsDialog.Addresses -> AddressesDialog(d.addresses, onDismiss = close) { list ->
            close()
            vm.changeDefaultDevice(d.copy(addresses = list), R.string.op_addresses) { putJsonArray("addresses") { list.forEach { add(JsonPrimitive(it)) } } }
        }
        DefaultsDialog.Recv -> SpeedLimitDialog(stringResource(R.string.download_limit), d.maxRecvKbps, onDismiss = close) { kib ->
            close(); vm.changeDefaultDevice(d.copy(maxRecvKbps = kib), R.string.op_download_limit) { put("maxRecvKbps", kib) }
        }
        DefaultsDialog.Send -> SpeedLimitDialog(stringResource(R.string.upload_limit), d.maxSendKbps, onDismiss = close) { kib ->
            close(); vm.changeDefaultDevice(d.copy(maxSendKbps = kib), R.string.op_upload_limit) { put("maxSendKbps", kib) }
        }
        DefaultsDialog.Compression -> ChoiceDialog(stringResource(R.string.compression), COMPRESSIONS, d.compression, ::compressionHint, onDismiss = close) { c ->
            close(); vm.changeDefaultDevice(d.copy(compression = c), R.string.op_compression) { put("compression", c) }
        }
        DefaultsDialog.Connections -> ChoiceDialog(
            stringResource(R.string.connections), connectionChoices(d.numConnections), d.numConnections.coerceAtLeast(0), onDismiss = close,
        ) { n -> close(); vm.changeDefaultDevice(d.copy(numConnections = n), R.string.op_connections) { put("numConnections", n) } }
        else -> {}
    }
}

@Composable
private fun Intro(text: String) = Text(
    text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
)

private fun JsonObjectBuilder.putVersioning(v: Versioning) = putJsonObject("versioning") {
    put("type", v.type)
    putJsonObject("params") { v.params.forEach { (k, value) -> put(k, value) } }
}
