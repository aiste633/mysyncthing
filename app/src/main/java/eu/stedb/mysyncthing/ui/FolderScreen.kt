package eu.stedb.mysyncthing.ui

import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.DiskSize
import eu.stedb.mysyncthing.core.FileError
import eu.stedb.mysyncthing.core.FolderStats
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.problemText
import eu.stedb.mysyncthing.core.text

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
    onOpenVersions: () -> Unit,
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
                        Text(status.text.asString(), style = MaterialTheme.typography.titleMedium, color = look.color)
                    }
                    if (st != null) {
                        Text(
                            stringResource(R.string.folder_size, bytes(st.localBytes), bytes(st.globalBytes)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    st?.error?.ifEmpty { null }?.let {
                        Text(problemText(it).asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    status.progress?.let { ProgressBar(it, look.color) }
                    if (st != null && f.needsReview(st)) {
                        val local = f.type == "receiveonly"
                        Text(
                            if (local) {
                                pluralStringResource(R.plurals.folder_local_changes, st.receiveOnlyTotalItems, grouped(st.receiveOnlyTotalItems), bytes(st.receiveOnlyChangedBytes))
                            } else {
                                pluralStringResource(R.plurals.folder_remote_changes, st.needTotalItems, grouped(st.needTotalItems))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(shape = ButtonShape, onClick = if (local) onOpenChanges else onOpenNeed) { Text(stringResource(R.string.folder_show_changes)) }
                            FilledTonalButton(shape = ButtonShape, onClick = { dialog = if (local) FolderDialog.Revert else FolderDialog.Override }) {
                                Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (local) R.string.folder_revert_button else R.string.folder_override_button))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        FilledTonalButton(shape = ButtonShape, onClick = { vm.setFolderPaused(id, !f.paused) }) {
                            Icon(if (f.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (f.paused) R.string.action_resume else R.string.action_pause))
                        }
                        FilledTonalButton(shape = ButtonShape, onClick = { vm.rescan(id) }, enabled = !f.paused) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_rescan))
                        }
                    }
                }
            }

            if (st != null && st.needTotalItems > 0 && !f.paused && f.type != "sendonly") {
                SettingsGroup(stringResource(R.string.folder_to_download)) {
                    SettingRow(
                        stringResource(R.string.items_and_size, count(R.plurals.count_items, st.needTotalItems).asString(), bytes(st.needBytes)),
                        stringResource(R.string.folder_see_files),
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, onClick = onOpenNeed,
                    )
                }
            }
            if (errors.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.failed_items)) {
                    errors.take(FAILED_PREVIEW).forEach { SettingRow(it.path, problemText(it.error).asString()) }
                    if (errors.size > FAILED_PREVIEW) {
                        SettingRow(
                            stringResource(R.string.folder_see_all, grouped(errors.size)), null,
                            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, onClick = onOpenFailed,
                        )
                    }
                }
            }

            SettingsGroup(stringResource(R.string.group_general)) {
                SettingRow(stringResource(R.string.name), f.label.ifEmpty { stringResource(R.string.name_none) }) { dialog = FolderDialog.Name }
                SettingRow(stringResource(R.string.group), f.group.ifEmpty { stringResource(R.string.group_none) }) { dialog = FolderDialog.Group }
                SettingRow(stringResource(R.string.folder_path), displayPath(f.path).asString())
                SettingRow(stringResource(R.string.type), folderTypeText(f.type).asString()) { dialog = FolderDialog.Type }
                SettingRow(stringResource(R.string.folder_id), f.id)
            }

            if (st != null) {
                SettingsGroup(stringResource(R.string.details)) {
                    SettingRow(stringResource(R.string.folder_last_scan), stats?.lastScan?.let { whenText(it) }?.asString() ?: stringResource(R.string.not_yet))
                    stats?.lastFile?.takeIf { it.filename.isNotEmpty() }?.let { lf ->
                        val what = if (lf.deleted) stringResource(R.string.folder_deleted_file, lf.filename) else lf.filename
                        SettingRow(stringResource(R.string.folder_last_change), listOfNotNull(what, whenText(lf.at)?.asString()).joinToString(" · "))
                    }
                    SettingRow(stringResource(R.string.on_this_phone), contents(st.localFiles, st.localDirectories, st.localBytes).asString())
                    SettingRow(stringResource(R.string.on_all_devices), contents(st.globalFiles, st.globalDirectories, st.globalBytes).asString())
                }
            }
            SettingsGroup(stringResource(R.string.shared_with)) {
                if (s.devices.isEmpty()) SettingRow(stringResource(R.string.share_no_devices), stringResource(R.string.share_no_devices_hint))
                s.devices.forEach { dev ->
                    val entry = f.devices.find { it.deviceID == dev.deviceID }
                    // A device's sync state only means something for folders it shares.
                    val state = if (entry != null) deviceStatus(dev, s.connections[dev.deviceID], null).text else text(R.string.item_not_shared)
                    CheckRow(
                        dev.name.ifEmpty { shortId(dev.deviceID) }, listOfNotNull(state, encryptionText(dev, entry)).map { it.asString() }.joinToString(" · "), entry != null,
                    ) { if (sharesEncrypted(dev, entry)) encrypt = dev.deviceID else vm.setShared(f, dev.deviceID, it) }
                }
            }

            SettingsGroup(stringResource(R.string.file_versioning)) {
                VersioningEditor(f.versioning) { vm.setVersioning(id, it) }
                if (f.versioning.type.isNotEmpty()) {
                    SettingRow(
                        stringResource(R.string.restore_versions), stringResource(R.string.restore_versions_hint),
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, onClick = onOpenVersions,
                    )
                }
            }

            SettingsGroup(stringResource(R.string.settings_group_advanced)) {
                SwitchRow(stringResource(R.string.folder_watch), stringResource(R.string.folder_watch_hint), f.fsWatcherEnabled) { vm.setWatcher(id, it) }
                SettingRow(
                    stringResource(R.string.full_rescan),
                    stringResource(R.string.label_and_hint, rescanText(f.rescanIntervalS).asString(), stringResource(R.string.folder_rescan_hint)),
                ) { dialog = FolderDialog.Rescan }
                SettingRow(stringResource(R.string.ignore_patterns), ignoresText(ignores).asString(), onClick = onOpenIgnores)
                SettingRow(stringResource(R.string.download_order), orderLabel(f.order).asString()) { dialog = FolderDialog.Order }
                SettingRow(
                    stringResource(R.string.keep_free),
                    stringResource(R.string.label_and_hint, diskSizeText(f.minDiskFree).asString(), stringResource(R.string.folder_keep_free_hint)),
                ) { dialog = FolderDialog.DiskFree }
            }

            SettingsGroup(stringResource(R.string.action_remove)) {
                SettingRow(stringResource(R.string.op_remove_folder), stringResource(R.string.folder_remove_hint), icon = Icons.Default.Delete, danger = true) {
                    dialog = FolderDialog.Remove
                }
            }
        }
    }

    when (dialog) {
        FolderDialog.Name -> TextDialog(stringResource(R.string.folder_name), f.label, stringResource(R.string.name), onDismiss = { dialog = null }) {
            vm.setFolderLabel(id, it); dialog = null
        }
        FolderDialog.Type -> ChoiceDialog(stringResource(R.string.folder_type), FOLDER_TYPES, f.type, ::folderTypeHint, onDismiss = { dialog = null }) {
            vm.setFolderType(id, it); dialog = null
        }
        FolderDialog.Group -> TextDialog(
            stringResource(R.string.folder_group), f.group, stringResource(R.string.group), supporting = stringResource(R.string.folder_group_hint),
            error = { null }, onDismiss = { dialog = null },
        ) { vm.setFolderGroup(id, it); dialog = null }
        FolderDialog.Rescan -> ChoiceDialog(stringResource(R.string.full_rescan), rescanChoices(f.rescanIntervalS), f.rescanIntervalS, ::rescanHint, onDismiss = { dialog = null }) {
            vm.setRescanInterval(id, it); dialog = null
        }
        FolderDialog.Order -> ChoiceDialog(stringResource(R.string.download_order), PULL_ORDERS, f.order, onDismiss = { dialog = null }) {
            vm.setPullOrder(id, it); dialog = null
        }
        FolderDialog.DiskFree -> DiskFreeDialog(f.minDiskFree, f.path, onDismiss = { dialog = null }) {
            vm.setMinDiskFree(id, it); dialog = null
        }
        FolderDialog.Revert -> ConfirmDialog(
            stringResource(R.string.folder_revert_title),
            revertText(st?.receiveOnlyTotalItems ?: 0).asString(),
            stringResource(R.string.action_revert), onDismiss = { dialog = null },
        ) { vm.revert(id); dialog = null }
        FolderDialog.Override -> ConfirmDialog(
            stringResource(R.string.folder_override_title),
            (st?.needTotalItems ?: 0).let { pluralStringResource(R.plurals.folder_override_text, it, grouped(it)) },
            stringResource(R.string.action_override), onDismiss = { dialog = null },
        ) { vm.override(id); dialog = null }
        FolderDialog.Remove -> ConfirmDialog(
            stringResource(R.string.folder_remove_title, f.label.ifEmpty { f.id }), stringResource(R.string.folder_remove_text),
            stringResource(R.string.action_remove), onDismiss = { dialog = null },
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
    text(R.string.folder_contents, count(R.plurals.count_files, files), count(R.plurals.count_folders, dirs), bytesText(size))

/** Amount plus unit (%, MB, GB, or the unit already set); shows what is free now for scale. */
@Composable
internal fun DiskFreeDialog(initial: DiskSize, path: String, onDismiss: () -> Unit, onSave: (DiskSize) -> Unit) {
    val locale = currentStrings().locale
    var value by rememberSaveable { mutableStateOf(fieldNumber(initial.value, locale)) }
    var unit by rememberSaveable { mutableStateOf(initial.unit) }
    val units = (listOf("%", "MB", "GB") + initial.unit).distinct()
    val free = remember(path) { runCatching { StatFs(path).let { it.availableBytes to it.totalBytes } }.getOrNull() }
    val error = amountError(value, unit)?.asString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keep_free)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value, { value = it }, label = { Text(stringResource(R.string.amount)) }, singleLine = true, isError = error != null,
                    supportingText = error?.let { { Text(it) } },
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
                    free?.let { (a, t) -> stringResource(R.string.keep_free_text_now, bytes(a), bytes(t)) } ?: stringResource(R.string.keep_free_text),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(DiskSize(decimal(value)!!, unit)) }, enabled = error == null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
