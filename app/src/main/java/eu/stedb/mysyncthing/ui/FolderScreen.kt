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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import android.os.StatFs
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import eu.stedb.mysyncthing.core.DiskSize
import eu.stedb.mysyncthing.core.FileError
import eu.stedb.mysyncthing.core.FolderStats
import eu.stedb.mysyncthing.core.Snapshot

private enum class FolderDialog { Name, Group, Type, Rescan, Order, DiskFree, Revert, Override, Remove }

/** Settings and status of one folder; every change applies at once, as in system Settings. */
@Composable
fun FolderScreen(
    s: Snapshot,
    id: String,
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenNeed: () -> Unit,
    onOpenFailed: () -> Unit,
    onOpenChanges: () -> Unit,
    onOpenIgnores: () -> Unit,
) {
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
    // The device whose password dialog is open, for an encrypted share.
    var encrypt by rememberSaveable { mutableStateOf<String?>(null) }
    var ignores by remember { mutableStateOf<List<String>?>(null) }
    var errors by remember { mutableStateOf<List<FileError>>(emptyList()) }
    LaunchedEffect(id, st?.errors) { errors = if ((st?.errors ?: 0) > 0) vm.folderErrors(id) else emptyList() }
    LaunchedEffect(id) { ignores = vm.ignores(id) }
    var stats by remember { mutableStateOf<FolderStats?>(null) }
    LaunchedEffect(id, st?.state) { stats = vm.folderStats(id) }

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
                    st?.error?.ifEmpty { null }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    status.progress?.let { ProgressBar(it, look.color) }
                    if (st != null && f.needsReview(st)) {
                        val local = f.type == "receiveonly"
                        Text(
                            if (local) {
                                "${count(st.receiveOnlyTotalItems, "item")}, ${bytes(st.receiveOnlyChangedBytes)}, changed on this phone. " +
                                    "This folder is receive only, so other devices don't get these changes."
                            } else {
                                "${count(st.needTotalItems, "item")} changed on other devices. " +
                                    "This folder is send only, so those changes aren't applied here."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(shape = ButtonShape, onClick = if (local) onOpenChanges else onOpenNeed) { Text("Show changes") }
                            FilledTonalButton(shape = ButtonShape, onClick = { dialog = if (local) FolderDialog.Revert else FolderDialog.Override }) {
                                Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (local) "Revert…" else "Override…")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        FilledTonalButton(shape = ButtonShape, onClick = { vm.setFolderPaused(id, !f.paused) }) {
                            Icon(if (f.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (f.paused) "Resume" else "Pause")
                        }
                        FilledTonalButton(shape = ButtonShape, onClick = { vm.rescan(id) }, enabled = !f.paused) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Rescan")
                        }
                    }
                }
            }

            if (st != null && st.needTotalItems > 0 && !f.paused && f.type != "sendonly") {
                SettingsGroup("Still to download") {
                    SettingRow(
                        "${count(st.needTotalItems, "item")} · ${bytes(st.needBytes)}", "See which files",
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, onClick = onOpenNeed,
                    )
                }
            }
            if (errors.isNotEmpty()) {
                SettingsGroup("Failed items") {
                    errors.take(FAILED_PREVIEW).forEach { SettingRow(it.path, it.error) }
                    if (errors.size > FAILED_PREVIEW) {
                        SettingRow(
                            "See all ${errors.size}", null,
                            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, onClick = onOpenFailed,
                        )
                    }
                }
            }

            SettingsGroup("General") {
                SettingRow("Name", f.label.ifEmpty { "(none)" }) { dialog = FolderDialog.Name }
                SettingRow("Group", f.group.ifEmpty { "None" }) { dialog = FolderDialog.Group }
                SettingRow("Folder on this phone", displayPath(f.path))
                SettingRow("Type", "${folderTypeLabel(f.type)}. ${folderTypeHint(f.type).orEmpty()}") { dialog = FolderDialog.Type }
                SettingRow("Folder ID", f.id)
            }

            if (st != null) {
                SettingsGroup("Details") {
                    SettingRow("Last scan", stats?.lastScan?.let { whenText(it) } ?: "Not yet")
                    stats?.lastFile?.takeIf { it.filename.isNotEmpty() }?.let { lf ->
                        val what = if (lf.deleted) "Deleted ${lf.filename}" else lf.filename
                        SettingRow("Last change", listOfNotNull(what, whenText(lf.at)).joinToString(" · "))
                    }
                    SettingRow("On this phone", contents(st.localFiles, st.localDirectories, st.localBytes))
                    SettingRow("On all devices", contents(st.globalFiles, st.globalDirectories, st.globalBytes))
                }
            }
            SettingsGroup("Shared with") {
                if (s.devices.isEmpty()) SettingRow("No devices added yet", "Add one on the Devices tab")
                s.devices.forEach { dev ->
                    val entry = f.devices.find { it.deviceID == dev.deviceID }
                    // A device's sync state only means something for folders it shares.
                    val state = if (entry != null) deviceStatus(dev, s.connections[dev.deviceID], null).text else "Not shared"
                    SettingRow(
                        dev.name.ifEmpty { shortId(dev.deviceID) }, listOfNotNull(state, encryptionText(dev, entry)).joinToString(" · "),
                        trailing = { Checkbox(entry != null, onCheckedChange = null) },
                    ) { if (sharesEncrypted(dev, entry)) encrypt = dev.deviceID else vm.setShared(f, dev.deviceID, entry == null) }
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
                SettingRow("Full rescan", "${rescanText(f.rescanIntervalS)}. Catches what watching misses.") { dialog = FolderDialog.Rescan }
                SettingRow(
                    "Ignore patterns",
                    ignores?.let { if (it.isEmpty()) "None" else count(it.size, "rule") } ?: "Loading",
                    onClick = onOpenIgnores,
                )
                SettingRow("Download order", orderLabel(f.order)) { dialog = FolderDialog.Order }
                SettingRow("Keep free on this phone", "${diskSizeText(f.minDiskFree)}. Syncing pauses below this.") { dialog = FolderDialog.DiskFree }
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
        FolderDialog.Group -> TextDialog(
            "Folder group", f.group, "Group", supporting = "Folders in a group are listed together. Only on this phone.",
            valid = { true }, onDismiss = { dialog = null },
        ) { vm.setFolderGroup(id, it); dialog = null }
        FolderDialog.Rescan -> ChoiceDialog("Full rescan", rescanChoices(f.rescanIntervalS), f.rescanIntervalS, ::rescanHint, onDismiss = { dialog = null }) {
            vm.setRescanInterval(id, it); dialog = null
        }
        FolderDialog.Order -> ChoiceDialog("Download order", PULL_ORDERS, f.order, onDismiss = { dialog = null }) {
            vm.setPullOrder(id, it); dialog = null
        }
        FolderDialog.DiskFree -> DiskFreeDialog(f.minDiskFree, f.path, onDismiss = { dialog = null }) {
            vm.setMinDiskFree(id, it); dialog = null
        }
        FolderDialog.Revert -> ConfirmDialog(
            "Revert local changes?",
            "${count(st?.receiveOnlyTotalItems ?: 0, "item")} changed on this phone are replaced with the version from your other devices, " +
                "or deleted if they don't exist there. This can't be undone.",
            "Revert", onDismiss = { dialog = null },
        ) { vm.revert(id); dialog = null }
        FolderDialog.Override -> ConfirmDialog(
            "Override changes?",
            "Your other devices get this phone's version of ${count(st?.needTotalItems ?: 0, "item")}. " +
                "The changes made to them elsewhere are lost. This can't be undone.",
            "Override", onDismiss = { dialog = null },
        ) { vm.override(id); dialog = null }
        FolderDialog.Remove -> ConfirmDialog(
            "Remove ${f.label.ifEmpty { f.id }}?", "Syncthing stops syncing this folder. Files on this phone are not deleted.",
            "Remove", onDismiss = { dialog = null },
        ) { vm.removeFolder(id); dialog = null; onBack() }
        null -> {}
    }
    s.devices.find { it.deviceID == encrypt }?.let { dev ->
        val entry = f.devices.find { it.deviceID == dev.deviceID }
        PasswordDialog(
            f.label.ifEmpty { f.id }, dev.name.ifEmpty { shortId(dev.deviceID) }, entry?.encryptionPassword.orEmpty(), shared = entry != null,
            onDismiss = { encrypt = null }, onUnshare = { vm.setShared(f, dev.deviceID, false); encrypt = null },
        ) { vm.setShared(f, dev.deviceID, true, it); encrypt = null }
    }
}

private const val FAILED_PREVIEW = 5

private fun contents(files: Int, dirs: Int, size: Long) =
    "${count(files, "file")} · ${count(dirs, "folder")} · ${bytes(size)}"

/** Amount plus unit (%, MB, GB, or the unit already set); shows what is free now for scale. */
@Composable
internal fun DiskFreeDialog(initial: DiskSize, path: String, onDismiss: () -> Unit, onSave: (DiskSize) -> Unit) {
    var value by rememberSaveable { mutableStateOf(number(initial.value)) }
    var unit by rememberSaveable { mutableStateOf(initial.unit) }
    val units = (listOf("%", "MB", "GB") + initial.unit).distinct()
    val free = remember(path) { runCatching { StatFs(path).let { it.availableBytes to it.totalBytes } }.getOrNull() }
    val v = value.replace(',', '.').toDoubleOrNull()
    val valid = v != null && v >= 0 && (unit != "%" || v <= 100)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep free on this phone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value, { value = it }, label = { Text("Amount") }, singleLine = true, isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    units.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = unit == u, onClick = { unit = u }, icon = {},
                            shape = SegmentedButtonDefaults.itemShape(i, units.size, MaterialTheme.shapes.extraSmall),
                        ) { Text(u) }
                    }
                }
                Text(
                    "Syncing into this folder pauses when less is free." +
                        (free?.let { (a, t) -> " Free now: ${bytes(a)} of ${bytes(t)}." } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(DiskSize(v!!, unit)) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
