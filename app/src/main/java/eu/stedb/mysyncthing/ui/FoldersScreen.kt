package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderStatus
import java.util.UUID

/** A folder to create, possibly pre-filled from a pending offer. */
data class FolderDraft(val id: String, val label: String, val path: String = "", val devices: Set<String> = emptySet())

@Composable
fun FoldersScreen(s: Snapshot, vm: MainViewModel) {
    var draft by remember { mutableStateOf<FolderDraft?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            s.pendingFolders.forEach { (id, pending) ->
                pending.offeredBy.forEach { (device, offer) ->
                    item("pending-$id-$device") {
                        val who = s.devices.find { it.deviceID == device }?.name?.ifEmpty { null } ?: shortId(device)
                        Card(Modifier.fillMaxWidth().padding(8.dp)) {
                            ListItem(
                                headlineContent = { Text("$who shares \"${offer.label.ifEmpty { id }}\"") },
                                trailingContent = {
                                    TextButton(onClick = { draft = FolderDraft(id, offer.label.ifEmpty { id }, devices = setOf(device)) }) {
                                        Text("Accept")
                                    }
                                },
                            )
                        }
                    }
                }
            }
            items(s.folders, key = { it.id }) { f -> FolderRow(f, s.folderStatus[f.id], vm) }
        }
        FloatingActionButton(
            onClick = { draft = FolderDraft(newFolderId(), "") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add folder") }
    }
    draft?.let { d ->
        FolderDialog(d, s, onDismiss = { draft = null }) {
            vm.addFolder(it.id, it.label, it.path, it.devices.toList())
            draft = null
        }
    }
}

@Composable
private fun FolderRow(f: Folder, st: FolderStatus?, vm: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable { menu = true },
            headlineContent = { Text(f.label.ifEmpty { f.id }) },
            supportingContent = {
                Column {
                    Text(f.path, style = MaterialTheme.typography.bodySmall)
                    val state = if (f.paused) "paused" else st?.state.orEmpty()
                    val need = st?.needBytes ?: 0
                    Text(if (need > 0) "$state · ${bytes(need)} to sync" else "$state · ${bytes(st?.localBytes ?: 0)}")
                    if (st != null && st.globalBytes > 0 && need > 0) {
                        LinearProgressIndicator(
                            progress = { 1f - need.toFloat() / st.globalBytes },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (f.paused) "Resume" else "Pause") }, onClick = { vm.setFolderPaused(f.id, !f.paused); menu = false })
            DropdownMenuItem(text = { Text("Rescan") }, onClick = { vm.rescan(f.id); menu = false })
            DropdownMenuItem(text = { Text("Remove (files stay)") }, onClick = { confirmRemove = true; menu = false })
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${f.label.ifEmpty { f.id }}?") },
            text = { Text("Syncthing stops syncing this folder. Files on disk are not deleted.") },
            confirmButton = { TextButton(onClick = { vm.removeFolder(f.id); confirmRemove = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FolderDialog(initial: FolderDraft, s: Snapshot, onDismiss: () -> Unit, onConfirm: (FolderDraft) -> Unit) {
    var d by remember { mutableStateOf(initial) }
    val pick = rememberFolderPicker { d = d.copy(path = it, label = d.label.ifEmpty { it.substringAfterLast('/') }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add folder") },
        text = {
            LazyColumn {
                item { OutlinedTextField(d.label, { d = d.copy(label = it) }, label = { Text("Label") }) }
                item { OutlinedTextField(d.id, { d = d.copy(id = it) }, label = { Text("Folder ID") }) }
                item {
                    OutlinedTextField(d.path, { d = d.copy(path = it) }, label = { Text("Path") })
                    TextButton(onClick = pick) { Text("Choose directory") }
                }
                item { Text("Share with", style = MaterialTheme.typography.titleSmall) }
                items(s.devices, key = { it.deviceID }) { dev ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = dev.deviceID in d.devices,
                            onCheckedChange = { on -> d = d.copy(devices = if (on) d.devices + dev.deviceID else d.devices - dev.deviceID) },
                        )
                        Text(dev.name.ifEmpty { shortId(dev.deviceID) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(d) }, enabled = d.id.isNotBlank() && d.path.startsWith("/")) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Upstream-style random ID: xxxxx-xxxxx. */
private fun newFolderId(): String = UUID.randomUUID().toString().replace("-", "").take(10).chunked(5).joinToString("-")
