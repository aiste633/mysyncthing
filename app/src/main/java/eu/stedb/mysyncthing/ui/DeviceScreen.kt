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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.Snapshot

/** Settings and status of one remote device. */
@Composable
fun DeviceScreen(s: Snapshot, id: String, vm: MainViewModel, onBack: () -> Unit) {
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

    AppScreen(title = d.name.ifEmpty { shortId(id) }, onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ListCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(look.icon, null, tint = look.color)
                        Spacer(Modifier.width(8.dp))
                        Text(status.text, style = MaterialTheme.typography.titleMedium, color = look.color)
                    }
                    connectionText(conn)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (conn?.connected == true) {
                        Text(
                            "Received ${bytes(conn.inBytesTotal)} · sent ${bytes(conn.outBytesTotal)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    status.progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth(), color = look.color) }
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
