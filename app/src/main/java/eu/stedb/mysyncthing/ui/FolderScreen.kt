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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import eu.stedb.mysyncthing.core.FileError
import eu.stedb.mysyncthing.core.Snapshot

private enum class FolderDialog { Name, Type, Ignores, Remove }

/** Settings and status of one folder; every change applies at once, as in system Settings. */
@Composable
fun FolderScreen(s: Snapshot, id: String, vm: MainViewModel, onBack: () -> Unit) {
    val f = s.folders.find { it.id == id }
    if (f == null) {
        // Removed, or the snapshot is reloading after a config change.
        LaunchedEffect(s.loaded) { if (s.loaded) onBack() }
        return
    }
    val st = s.folderStatus[id]
    val status = folderStatus(f, st)
    val look = status.state.look()
    var dialog by rememberSaveable { mutableStateOf<FolderDialog?>(null) }
    var ignores by remember { mutableStateOf<List<String>?>(null) }
    var errors by remember { mutableStateOf<List<FileError>>(emptyList()) }
    LaunchedEffect(id, st?.errors) { errors = if ((st?.errors ?: 0) > 0) vm.folderErrors(id) else emptyList() }
    LaunchedEffect(id) { ignores = vm.ignores(id) }

    AppScreen(title = f.label.ifEmpty { f.id }, onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ListCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(look.icon, null, tint = look.color)
                        Spacer(Modifier.width(8.dp))
                        Text(status.text, style = MaterialTheme.typography.titleMedium, color = look.color)
                    }
                    if (st != null) {
                        Text(
                            "${bytes(st.localBytes)} on this phone of ${bytes(st.globalBytes)}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    status.progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth(), color = look.color) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        FilledTonalButton(onClick = { vm.setFolderPaused(id, !f.paused) }) {
                            Icon(if (f.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (f.paused) "Resume" else "Pause")
                        }
                        FilledTonalButton(onClick = { vm.rescan(id) }, enabled = !f.paused) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Rescan")
                        }
                    }
                }
            }

            if (errors.isNotEmpty()) {
                SettingsGroup("Failed items") {
                    errors.take(20).forEach { SettingRow(it.path, it.error) }
                    if (errors.size > 20) SettingRow("${errors.size - 20} more", "See the web GUI for the full list")
                }
            }

            SettingsGroup("General") {
                SettingRow("Name", f.label.ifEmpty { "(none)" }) { dialog = FolderDialog.Name }
                SettingRow("Folder on this phone", displayPath(f.path))
                SettingRow("Type", "${folderTypeLabel(f.type)}. ${folderTypeHint(f.type).orEmpty()}") { dialog = FolderDialog.Type }
                SettingRow("Folder ID", f.id)
            }

            SettingsGroup("Shared with") {
                if (s.devices.isEmpty()) SettingRow("No devices added yet", "Add one on the Devices tab")
                s.devices.forEach { dev ->
                    val on = f.devices.any { it.deviceID == dev.deviceID }
                    val ds = deviceStatus(dev, s.connections[dev.deviceID], null)
                    SettingRow(
                        dev.name.ifEmpty { shortId(dev.deviceID) }, ds.text,
                        trailing = { Checkbox(on, onCheckedChange = null) },
                    ) { vm.setShared(f, dev.deviceID, !on) }
                }
            }

            SettingsGroup("File versioning") {
                VersioningEditor(f.versioning) { vm.setVersioning(id, it) }
            }

            SettingsGroup("Advanced") {
                SettingRow(
                    "Watch for changes", "Sync edits within seconds instead of at the next scan",
                    trailing = { Switch(f.fsWatcherEnabled, onCheckedChange = null) },
                ) { vm.setWatcher(id, !f.fsWatcherEnabled) }
                SettingRow(
                    "Ignore patterns",
                    ignores?.let { if (it.isEmpty()) "None" else "${it.size} ${if (it.size == 1) "rule" else "rules"}" } ?: "Loading",
                ) { if (ignores != null) dialog = FolderDialog.Ignores }
            }

            SettingsGroup("Remove") {
                SettingRow("Remove folder", "Stops syncing it. Files on this phone stay.", icon = Icons.Default.Delete, danger = true) {
                    dialog = FolderDialog.Remove
                }
            }
        }
    }

    when (dialog) {
        FolderDialog.Name -> TextDialog("Folder name", f.label, "Name", onDismiss = { dialog = null }) {
            vm.setFolderLabel(id, it); dialog = null
        }
        FolderDialog.Type -> ChoiceDialog("Folder type", FOLDER_TYPES, f.type, ::folderTypeHint, onDismiss = { dialog = null }) {
            vm.setFolderType(id, it); dialog = null
        }
        FolderDialog.Ignores -> TextDialog(
            "Ignore patterns", ignores.orEmpty().joinToString("\n"), "One pattern per line", singleLine = false,
            supporting = "Example: *.tmp or /Thumbnails", valid = { true }, onDismiss = { dialog = null },
        ) { text ->
            val lines = text.lines().map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
            vm.setIgnores(id, lines); ignores = lines; dialog = null
        }
        FolderDialog.Remove -> ConfirmDialog(
            "Remove ${f.label.ifEmpty { f.id }}?", "Syncthing stops syncing this folder. Files on this phone are not deleted.",
            "Remove", onDismiss = { dialog = null },
        ) { vm.removeFolder(id); dialog = null; onBack() }
        null -> {}
    }
}
