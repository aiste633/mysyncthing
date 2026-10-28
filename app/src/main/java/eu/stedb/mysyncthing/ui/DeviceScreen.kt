package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Completion
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.text

/** Settings and status of one remote device. */
@Composable
fun DeviceScreen(s: Snapshot, id: String, vm: MainViewModel, onBack: () -> Unit, onOpenNeeds: (folder: String) -> Unit) {
    val d = s.devices.find { it.deviceID == id }
    if (d == null) {
        LaunchedEffect(s.loaded) { if (s.loaded) onBack() }
        return
    }
    val conn = s.connections[id]
    val status = deviceStatus(d, conn, s.completion[id])
    val look = status.state.look()
    var dialog by rememberSaveable { mutableStateOf<DeviceDialog?>(null) }
    // The folder whose password dialog is open, for an encrypted share.
    var encrypt by rememberSaveable { mutableStateOf<String?>(null) }
    val live by remember(vm) { vm.liveStats() }.collectAsStateWithLifecycle(null)
    var lastSeen by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id, conn?.connected) { lastSeen = vm.deviceStats(id)?.lastSeen }
    // Per folder, what this device still needs from the phone; reread when its overall completion changes.
    val shared = s.folders.filter { f -> f.devices.any { it.deviceID == id } }
    var needs by remember { mutableStateOf<Map<String, Completion>>(emptyMap()) }
    val total = s.completion[id]
    LaunchedEffect(id, total, shared.map { it.id }) {
        needs = if (total != null && (total.needItems > 0 || total.needBytes > 0)) vm.remoteCompletion(id, shared.map { it.id }) else emptyMap()
    }

    AppScreen(title = d.name.ifEmpty { shortId(id) }, onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ListCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(look.icon, null, tint = look.color)
                        Spacer(Modifier.width(8.dp))
                        Text(status.text.asString(), style = MaterialTheme.typography.titleMedium, color = look.color)
                    }
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    connectionText(conn)?.let {
                        Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                    if (conn?.connected == true) {
                        live?.devices?.get(id)?.let { Text(speedText(it).asString(), style = MaterialTheme.typography.bodyMedium, color = muted) }
                        Text(
                            stringResource(R.string.device_traffic, bytes(conn.inBytesTotal), bytes(conn.outBytesTotal)),
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    } else {
                        val seen = lastSeen?.let { whenText(it) }?.asString()
                        Text(if (seen != null) stringResource(R.string.device_last_seen, seen) else stringResource(R.string.device_never_connected), style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                    conn?.clientVersion?.ifEmpty { null }?.let {
                        Text(if (it.startsWith("v")) stringResource(R.string.syncthing_version, it) else it, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    if (total != null && total.needItems > 0) {
                        Text(
                            pluralStringResource(R.plurals.device_still_needs, total.needItems, grouped(total.needItems), bytes(total.needBytes)),
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                    status.progress?.let { ProgressBar(it, look.color) }
                    FilledTonalButton(shape = ButtonShape, onClick = { vm.setDevicePaused(id, !d.paused) }, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(if (d.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (d.paused) R.string.action_resume else R.string.action_pause))
                    }
                }
            }

            SettingsGroup(stringResource(R.string.group_trust)) {
                SwitchRow(stringResource(R.string.device_untrusted), stringResource(R.string.device_untrusted_hint), d.untrusted) { v ->
                    if (v) dialog = DeviceDialog.Untrusted else vm.setUntrusted(id, false)
                }
                // The core allows neither for an untrusted device.
                if (!d.untrusted) {
                    SwitchRow(stringResource(R.string.device_introducer), stringResource(R.string.device_introducer_hint), d.introducer) { vm.setIntroducer(id, it) }
                    SwitchRow(stringResource(R.string.device_auto_accept), stringResource(R.string.device_auto_accept_hint), d.autoAcceptFolders) {
                        vm.setAutoAccept(id, it)
                    }
                }
            }

            SettingsGroup(stringResource(R.string.group_general)) {
                SettingRow(stringResource(R.string.name), d.name.ifEmpty { stringResource(R.string.name_none) }) { dialog = DeviceDialog.Name }
                SettingRow(stringResource(R.string.group), d.group.ifEmpty { stringResource(R.string.group_none) }) { dialog = DeviceDialog.Group }
                SettingRow(stringResource(R.string.device_addresses), addressesText(d.addresses)) { dialog = DeviceDialog.Addresses }
                SelectionContainer { SettingRow(stringResource(R.string.device_id), id) }
            }

            if (needs.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.device_to_send)) {
                    shared.filter { it.id in needs }.forEach { f ->
                        val c = needs.getValue(f.id)
                        SettingRow(
                            f.label.ifEmpty { f.id }, stringResource(R.string.items_and_size, count(R.plurals.count_items, c.needItems).asString(), bytes(c.needBytes)),
                            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        ) { onOpenNeeds(f.id) }
                    }
                }
            }
            SettingsGroup(stringResource(R.string.device_shared_folders)) {
                if (s.folders.isEmpty()) SettingRow(stringResource(R.string.folders_empty), stringResource(R.string.device_no_folders_hint))
                s.folders.forEach { f ->
                    val entry = f.devices.find { it.deviceID == id }
                    CheckRow(f.label.ifEmpty { f.id }, (encryptionText(d, entry) ?: displayPath(f.path)).asString(), entry != null) {
                        if (sharesEncrypted(d, entry)) encrypt = f.id else vm.setShared(f, id, it)
                    }
                }
            }

            SettingsGroup(stringResource(R.string.settings_group_advanced)) {
                SettingRow(stringResource(R.string.download_limit), limitText(d.maxRecvKbps).asString()) { dialog = DeviceDialog.Recv }
                SettingRow(stringResource(R.string.upload_limit), limitText(d.maxSendKbps).asString()) { dialog = DeviceDialog.Send }
                SettingRow(stringResource(R.string.compression), compressionLabel(d.compression).asString()) { dialog = DeviceDialog.Compression }
                SettingRow(stringResource(R.string.connections), connectionsText(d.numConnections).asString()) { dialog = DeviceDialog.Connections }
            }

            SettingsGroup(stringResource(R.string.action_remove)) {
                SettingRow(stringResource(R.string.device_remove), stringResource(R.string.device_remove_hint), icon = Icons.Default.Delete, danger = true) {
                    dialog = DeviceDialog.Remove
                }
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        DeviceDialog.Name -> TextDialog(stringResource(R.string.settings_device_name), d.name, stringResource(R.string.name), onDismiss = close) { vm.renameDevice(id, it); close() }
        DeviceDialog.Group -> TextDialog(
            stringResource(R.string.device_group), d.group, stringResource(R.string.group), supporting = stringResource(R.string.device_group_hint),
            error = { null }, onDismiss = close,
        ) { vm.setDeviceGroup(id, it); close() }
        DeviceDialog.Addresses -> AddressesDialog(d.addresses, onDismiss = close) { vm.setAddresses(id, it); close() }
        DeviceDialog.Recv -> SpeedLimitDialog(stringResource(R.string.download_limit), d.maxRecvKbps, onDismiss = close) { vm.setDeviceRecvLimit(id, it); close() }
        DeviceDialog.Send -> SpeedLimitDialog(stringResource(R.string.upload_limit), d.maxSendKbps, onDismiss = close) { vm.setDeviceSendLimit(id, it); close() }
        DeviceDialog.Compression -> ChoiceDialog(stringResource(R.string.compression), COMPRESSIONS, d.compression, ::compressionHint, onDismiss = close) {
            vm.setCompression(id, it); close()
        }
        DeviceDialog.Connections -> ChoiceDialog(
            stringResource(R.string.connections), connectionChoices(d.numConnections), d.numConnections.coerceAtLeast(0),
            { if (it == 0) text(R.string.connections_auto_hint) else null }, onDismiss = close,
        ) { vm.setNumConnections(id, it); close() }
        DeviceDialog.Untrusted -> {
            val plain = s.folders.filter { f -> f.devices.any { it.deviceID == id && it.encryptionPassword.isEmpty() } }
            val name = d.name.ifEmpty { shortId(id) }
            ConfirmDialog(
                stringResource(R.string.device_untrusted_title),
                if (plain.isEmpty()) stringResource(R.string.device_untrusted_text, name)
                else stringResource(R.string.device_untrusted_text_plain, name, plain.joinToString(", ") { it.label.ifEmpty { it.id } }),
                stringResource(R.string.device_untrusted_confirm), onDismiss = close,
            ) { vm.setUntrusted(id, true); close() }
        }
        DeviceDialog.Remove -> ConfirmDialog(
            stringResource(R.string.folder_remove_title, d.name.ifEmpty { shortId(id) }), stringResource(R.string.device_remove_text),
            stringResource(R.string.action_remove), onDismiss = close,
        ) { vm.removeDevice(id); close(); onBack() }
        null -> {}
    }
    s.folders.find { it.id == encrypt }?.let { f ->
        val entry = f.devices.find { it.deviceID == id }
        PasswordDialog(
            f.label.ifEmpty { f.id }, d.name.ifEmpty { shortId(id) }, entry?.encryptionPassword.orEmpty(), shared = entry != null,
            onDismiss = { encrypt = null }, onUnshare = { vm.setShared(f, id, false); encrypt = null },
        ) { vm.setShared(f, id, true, it); encrypt = null }
    }
}

private enum class DeviceDialog { Name, Group, Addresses, Recv, Send, Compression, Connections, Untrusted, Remove }

@Composable
internal fun addressesText(a: List<String>): String = when {
    a.isEmpty() || a == listOf("dynamic") -> stringResource(R.string.addresses_automatic)
    else -> a.map { if (it == "dynamic") stringResource(R.string.address_automatic) else it }.joinToString(", ")
}

/** "Find automatically" plus fixed addresses Syncthing tries first. */
@Composable
internal fun AddressesDialog(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) = ListEditDialog(
    stringResource(R.string.device_addresses), initial, token = "dynamic", tokenTitle = stringResource(R.string.addresses_find),
    tokenHint = stringResource(R.string.addresses_find_hint), addHint = stringResource(R.string.addresses_add_hint), valid = ::isDeviceAddress, normalize = ::normalizeAddress,
    onDismiss = onDismiss, onSave = onSave,
)
