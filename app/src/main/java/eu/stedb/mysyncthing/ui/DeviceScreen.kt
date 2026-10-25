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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.core.Completion
import eu.stedb.mysyncthing.core.Snapshot

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
    var rename by rememberSaveable { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf(false) }
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
                        Text(status.text, style = MaterialTheme.typography.titleMedium, color = look.color)
                    }
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    connectionText(conn)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                    if (conn?.connected == true) {
                        live?.devices?.get(id)?.let { Text(speedText(it), style = MaterialTheme.typography.bodyMedium, color = muted) }
                        Text(
                            "Received ${bytes(conn.inBytesTotal)} · sent ${bytes(conn.outBytesTotal)}",
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    } else {
                        val seen = lastSeen?.let { whenText(it) }
                        Text(if (seen != null) "Last seen $seen" else "Never connected", style = MaterialTheme.typography.bodyMedium, color = muted)
                    }
                    conn?.clientVersion?.ifEmpty { null }?.let {
                        Text(if (it.startsWith("v")) "Syncthing $it" else it, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    if (total != null && total.needItems > 0) {
                        Text(
                            "Still needs ${count(total.needItems, "item")}, ${bytes(total.needBytes)} from this phone",
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                    status.progress?.let { ProgressBar(it, look.color) }
                    FilledTonalButton(shape = ButtonShape, onClick = { vm.setDevicePaused(id, !d.paused) }, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(if (d.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (d.paused) "Resume" else "Pause")
                    }
                }
            }

            SettingsGroup("General") {
                SettingRow("Name", d.name.ifEmpty { "(none)" }) { rename = true }
                SelectionContainer { SettingRow("Device ID", id) }
            }

            if (needs.isNotEmpty()) {
                SettingsGroup("Still to send") {
                    shared.filter { it.id in needs }.forEach { f ->
                        val c = needs.getValue(f.id)
                        SettingRow(
                            f.label.ifEmpty { f.id }, "${count(c.needItems, "item")} · ${bytes(c.needBytes)}",
                            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        ) { onOpenNeeds(f.id) }
                    }
                }
            }
            SettingsGroup("Shared folders") {
                if (s.folders.isEmpty()) SettingRow("No folders yet", "Add one on the Folders tab")
                s.folders.forEach { f ->
                    val on = f.devices.any { it.deviceID == id }
                    SettingRow(
                        f.label.ifEmpty { f.id }, displayPath(f.path),
                        trailing = { Checkbox(on, onCheckedChange = null) },
                    ) { vm.setShared(f, id, !on) }
                }
            }

            SettingsGroup("Remove") {
                SettingRow("Remove device", "It can no longer sync with this phone.", icon = Icons.Default.Delete, danger = true) {
                    remove = true
                }
            }
        }
    }

    if (rename) {
        TextDialog("Device name", d.name, "Name", onDismiss = { rename = false }) { vm.renameDevice(id, it); rename = false }
    }
    if (remove) {
        ConfirmDialog(
            "Remove ${d.name.ifEmpty { shortId(id) }}?", "Folders stop syncing with this device. No files are deleted.",
            "Remove", onDismiss = { remove = false },
        ) { vm.removeDevice(id); remove = false; onBack() }
    }
}
