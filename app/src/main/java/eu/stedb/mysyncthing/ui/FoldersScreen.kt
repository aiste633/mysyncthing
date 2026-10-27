package eu.stedb.mysyncthing.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.R as CoreR
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    onOpenRecent: () -> Unit,
) {
    AppScreen(
        title = stringResource(R.string.tab_folders),
        status = summary,
        actions = {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.folders_rescan_all)) }, enabled = s.folders.any { !it.paused },
                    onClick = { menu = false; vm.rescanAll() },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.recent_changes)) }, onClick = { menu = false; onOpenRecent() })
            }
        },
        fab = { AddFab(stringResource(R.string.folders_add), onAdd) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (s.loaded && s.folders.isEmpty() && s.pendingFolders.isEmpty()) {
                EmptyState(
                    Icons.Default.FolderOpen, stringResource(R.string.folders_empty),
                    stringResource(R.string.folders_empty_hint),
                )
            }
            val list = rememberLazyListState()
            // Problem and request cards go above the folders. The list keeps its first visible
            // item in place, so at the top a new card would land just above the screen: scroll to it.
            val topCards = (if (s.systemErrors.isNotEmpty()) 1 else 0) + s.pendingFolders.values.sumOf { it.offeredBy.size }
            var shownTop by remember { mutableIntStateOf(topCards) }
            LaunchedEffect(topCards) {
                val added = topCards - shownTop
                shownTop = topCards
                if (added > 0 && list.firstVisibleItemIndex <= added) list.scrollToItem(0)
            }
            LazyColumn(state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                if (s.systemErrors.isNotEmpty()) item("problems") { ProblemsCard(s.systemErrors, onClear = vm::clearErrors) }
                s.pendingFolders.forEach { (id, pending) ->
                    pending.offeredBy.forEach { (device, offer) ->
                        item("pending-$id-$device") {
                            val dev = s.devices.find { it.deviceID == device }
                            val who = dev?.name?.ifEmpty { null } ?: shortId(device)
                            // The core also lists a folder this phone has but doesn't share with that device yet.
                            val existing = s.folders.find { it.id == id }
                            val title = stringResource(R.string.folders_offer, who, offer.label.ifEmpty { id })
                            if (existing == null) {
                                RequestCard(title, stringResource(R.string.folders_offer_choose)) {
                                    TextButton(onClick = { vm.ignoreFolder(id, device) }) { Text(stringResource(CoreR.string.action_ignore)) }
                                    TextButton(onClick = { onAccept(id, device) }) { Text(stringResource(CoreR.string.action_review)) }
                                }
                            } else {
                                RequestCard(title, stringResource(R.string.folders_offer_have, displayPath(existing.path).asString(), who)) {
                                    TextButton(onClick = { vm.ignoreFolder(id, device) }) { Text(stringResource(CoreR.string.action_ignore)) }
                                    TextButton(onClick = {
                                        // An untrusted device needs a password, asked on the folder's screen.
                                        if (dev != null && sharesEncrypted(dev, null)) onOpen(id) else vm.setShared(existing, device, true)
                                    }) { Text(stringResource(R.string.action_share)) }
                                }
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
                Text(status.text.asString(), style = MaterialTheme.typography.labelLarge, color = look.color)
            }
            Text(
                displayPath(f.path).asString(), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                st?.let { bytes(it.localBytes) },
                folderTypeLabel(f.type).takeIf { f.type != "sendreceive" }?.asString(),
                if (shared == 0) stringResource(R.string.folder_card_not_shared) else pluralStringResource(R.plurals.folder_card_shared, shared, shared),
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            status.progress?.let {
                ProgressBar(it, look.color, Modifier.padding(top = 6.dp))
            }
        }
    }
}
