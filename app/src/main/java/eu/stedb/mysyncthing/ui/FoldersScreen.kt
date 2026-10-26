package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderStatus
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Summary

@Composable
fun FoldersScreen(
    s: Snapshot,
    summary: Summary,
    vm: MainViewModel,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onAccept: (folder: String, device: String) -> Unit,
) {
    AppScreen(
        title = "Folders",
        actions = {
            StatusChip(summary)
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rescan all folders") }, enabled = s.folders.any { !it.paused },
                    onClick = { menu = false; vm.rescanAll() },
                )
            }
        },
        fab = { ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add folder") }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (s.loaded && s.folders.isEmpty() && s.pendingFolders.isEmpty()) {
                EmptyState(
                    Icons.Default.FolderOpen, "No folders yet",
                    "Tap Add folder to sync a folder on this phone. Folders other devices share with you appear here too.",
                )
            }
            val list = rememberLazyListState()
            // A new problem card goes above the first item; at the top of the list, keep it in view.
            val hasProblems = s.systemErrors.isNotEmpty()
            LaunchedEffect(hasProblems) { if (hasProblems && list.firstVisibleItemIndex <= 1) list.scrollToItem(0) }
            LazyColumn(state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                if (s.systemErrors.isNotEmpty()) item("problems") { ProblemsCard(s.systemErrors, onClear = vm::clearErrors) }
                s.pendingFolders.forEach { (id, pending) ->
                    pending.offeredBy.forEach { (device, offer) ->
                        item("pending-$id-$device") {
                            val who = s.devices.find { it.deviceID == device }?.name?.ifEmpty { null } ?: shortId(device)
                            RequestCard("$who shares \"${offer.label.ifEmpty { id }}\"", "Choose where to keep it on this phone") {
                                TextButton(onClick = { vm.ignoreFolder(id, device) }) { Text("Ignore") }
                                TextButton(onClick = { onAccept(id, device) }) { Text("Review") }
                            }
                        }
                    }
                }
                grouped(s.folders) { it.group }.forEach { (group, folders) ->
                    if (group.isNotEmpty()) item("group-$group") { GroupTitle(group) }
                    items(folders, key = { it.id }) { f -> FolderCard(f, s.folderStatus[f.id], s) { onOpen(f.id) } }
                }
            }
        }
    }
}

@Composable
private fun FolderCard(f: Folder, st: FolderStatus?, s: Snapshot, onClick: () -> Unit) {
    val status = folderStatus(f, st)
    val look = status.state.look()
    val shared = f.devices.count { it.deviceID != s.myId }
    ListCard(onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.label.ifEmpty { f.id }, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(look.icon, null, Modifier.size(18.dp), tint = look.color)
                Spacer(Modifier.width(4.dp))
                Text(status.text, style = MaterialTheme.typography.labelLarge, color = look.color)
            }
            Text(
                displayPath(f.path), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                st?.let { bytes(it.localBytes) },
                folderTypeLabel(f.type).takeIf { f.type != "sendreceive" },
                if (shared == 0) "not shared" else "shared with $shared",
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            status.progress?.let {
                ProgressBar(it, look.color, Modifier.padding(top = 6.dp))
            }
        }
    }
}
