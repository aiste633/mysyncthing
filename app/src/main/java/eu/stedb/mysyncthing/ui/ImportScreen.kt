package eu.stedb.mysyncthing.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.ForkDevice
import eu.stedb.mysyncthing.core.ForkFolder
import eu.stedb.mysyncthing.core.ForkImport
import eu.stedb.mysyncthing.core.ForkNotImported
import eu.stedb.mysyncthing.core.ForkPreview
import eu.stedb.mysyncthing.core.PowerSource
import eu.stedb.mysyncthing.core.ProxySettings
import eu.stedb.mysyncthing.core.ProxyType
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.joined
import eu.stedb.mysyncthing.core.plural
import eu.stedb.mysyncthing.core.raw
import eu.stedb.mysyncthing.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Import of this app's export or a Syncthing-Fork backup: pick the file, enter its password if it
 * has one, review what is imported and what is not, then replace this phone's
 * setup. The steps live in [ImportViewModel].
 */
@Composable
fun ImportScreen(onBack: () -> Unit, vm: ImportViewModel = viewModel()) {
    val step by vm.step.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.inspect(it, "") } }
    val working = step is ImportStep.Working
    val back: () -> Unit = { if (step is ImportStep.Choose || step is ImportStep.Done) onBack() else vm.restart() }
    // While working, Back is held here: leaving would drop the result of the import.
    BackHandler { if (!working) back() }

    AppScreen(stringResource(R.string.import_title), onBack = if (working) null else back) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            when (val s = step) {
                ImportStep.Choose -> ChooseStep(onFound = { vm.inspect(Uri.fromFile(it), "") }) {
                    pick.launch(arrayOf("application/zip", "application/octet-stream"))
                }
                // Keyed, so a new attempt starts with the tried password and its error shown.
                is ImportStep.Password -> key(s.attempt) {
                    PasswordStep(s.tried, s.wrong, vm.password, { vm.password = it }) { vm.inspect(s.uri, vm.password) }
                }
                is ImportStep.Review -> ReviewStep(s.preview) { keepIdentity, deleteBackup ->
                    vm.import(s, keepIdentity, deleteBackup)
                }
                is ImportStep.Working -> Column(
                    Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BusyIndicator()
                    Text(s.what.asString())
                }
                is ImportStep.Done -> Note(
                    stringResource(if (s.problem == null) R.string.import_done else R.string.import_done_problem),
                    listOfNotNull(
                        s.problem,
                        text(R.string.import_done_text),
                        if (s.keepIdentity) text(R.string.import_done_identity) else null,
                    ).joined("\n\n").asString(),
                )
                is ImportStep.Failed -> {
                    Note(stringResource(R.string.import_failed), s.message.asString())
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        OutlinedButton(shape = ButtonShape, onClick = vm::restart) { Text(stringResource(R.string.import_choose_another)) }
                    }
                }
            }
        }
    }
}

private class FoundBackup(val file: File, val modified: Long, val size: Long)

@Composable
private fun ChooseStep(onFound: (File) -> Unit, onPick: () -> Unit) {
    // Looked up off the main thread: it is on shared storage.
    val found by produceState<FoundBackup?>(null) {
        value = withContext(Dispatchers.IO) {
            ForkImport.defaultBackup.takeIf { it.isFile }?.let { FoundBackup(it, it.lastModified(), it.length()) }
        }
    }
    Note(
        stringResource(R.string.import_intro_title),
        stringResource(R.string.import_intro),
    )
    found?.let {
        SettingsGroup(stringResource(R.string.import_found)) {
            SettingRow(
                wrapPath(displayPath(it.file.path).asString()),
                "${dateTimeText(it.modified).asString()}, ${bytes(it.size)}",
                Icons.Default.Folder,
            ) { onFound(it.file) }
        }
    }
    Row(Modifier.padding(16.dp)) {
        Button(shape = ButtonShape, onClick = onPick) {
            Icon(Icons.Default.FolderOpen, null)
            Text(stringResource(R.string.import_choose), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun PasswordStep(tried: String, wrong: Boolean, password: String, onChange: (String) -> Unit, onContinue: () -> Unit) {
    // The text itself stays in the ViewModel: saved state must not hold it.
    var show by rememberSaveable { mutableStateOf(false) }
    var edited by rememberSaveable { mutableStateOf(false) }
    val problem = backupPasswordError(password, wrong && password == tried)
    Note(stringResource(R.string.import_password_title), stringResource(R.string.import_password_text))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            password, { onChange(it); edited = true },
            label = { Text(stringResource(R.string.import_password_label)) }, singleLine = true,
            isError = problem != null && (edited || wrong),
            supportingText = problem?.takeIf { edited || wrong }?.let { { Text(it.asString()) } },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                PasswordVisibilityButton(show) { show = !show }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(shape = ButtonShape, enabled = backupPasswordError(password, false) == null, onClick = onContinue) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

@Composable
private fun ReviewStep(p: ForkPreview, onImport: (keepIdentity: Boolean, deleteBackup: Boolean) -> Unit) {
    var keepIdentity by rememberSaveable { mutableStateOf(true) }
    var deleteBackup by rememberSaveable { mutableStateOf(true) }
    var confirm by rememberSaveable { mutableStateOf(false) }

    SettingsGroup(stringResource(R.string.device_id)) {
        RadioRow(
            stringResource(R.string.import_take_over, p.deviceName.ifEmpty { shortId(p.deviceID) }, shortId(p.deviceID)),
            stringResource(if (p.source == "own") R.string.import_take_over_own_hint else R.string.import_take_over_fork_hint),
            keepIdentity,
        ) { keepIdentity = true }
        RadioRow(
            stringResource(R.string.import_keep_id),
            stringResource(R.string.import_keep_id_hint),
            !keepIdentity,
        ) { keepIdentity = false }
    }
    SettingsGroup(count(R.plurals.count_folders, p.folders.size).asString()) {
        p.folders.forEach { f -> SettingRow(f.label.ifEmpty { f.id }, folderImportText(f).asString(), Icons.Default.Folder) }
    }
    SettingsGroup(count(R.plurals.count_devices, p.devices.size).asString()) {
        p.devices.forEach { d -> SettingRow(d.name.ifEmpty { shortId(d.id) }, deviceImportText(d), Icons.Default.Devices) }
    }
    SettingsGroup(stringResource(R.string.import_sync_settings)) {
        conditionsText(p.conditions, p.proxy).forEach { SettingRow(it.asString()) }
    }
    if (p.notImported.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.import_not_imported)) {
            p.notImported.forEach { SettingRow(notImportedText(it).asString()) }
        }
    }

    // The file holds the private key in shared storage, readable by apps with All files access.
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                stringResource(R.string.import_key_warning),
                color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 12.dp),
            )
        }
        CheckRow(stringResource(R.string.import_delete_backup), stringResource(R.string.permission_recommended), deleteBackup) { deleteBackup = it }
    }
    Row(Modifier.padding(horizontal = 16.dp)) {
        Button(shape = ButtonShape, onClick = { confirm = true }) { Text(stringResource(R.string.action_import)) }
    }

    if (confirm) {
        ConfirmDialog(
            stringResource(R.string.import_replace_title),
            stringResource(R.string.import_replace_text),
            stringResource(R.string.action_replace), onDismiss = { confirm = false },
        ) {
            confirm = false
            onImport(keepIdentity, deleteBackup)
        }
    }
}

@Composable
private fun Note(title: String, text: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Longest password accepted; zip4j itself sets none. */
const val MAX_BACKUP_PASSWORD = 1024

/**
 * Why [password] can't be tried, or null. It is used exactly as typed: spaces
 * count, as they did when Fork encrypted the backup.
 */
fun backupPasswordError(password: String, wrong: Boolean): UiText? = when {
    wrong -> text(R.string.error_password_wrong)
    password.isEmpty() -> text(R.string.error_password_backup_empty)
    password.length > MAX_BACKUP_PASSWORD -> plural(R.plurals.error_too_long_chars, MAX_BACKUP_PASSWORD)
    else -> null
}

/** What happens to an imported folder on this phone (design: the core creates what is missing). */
fun folderImportText(f: ForkFolder): UiText = text(
    when {
        f.exists && f.marker -> R.string.import_folder_found
        f.exists -> R.string.import_folder_found_new
        f.type == "sendonly" -> R.string.import_folder_create_empty
        else -> R.string.import_folder_create
    },
    displayPath(f.path).let { if (it is UiText.Raw) raw(wrapPath(it.text)) else it },
)

fun conditionsText(c: RunConditions, proxy: ProxySettings): List<UiText> = listOfNotNull(
    text(
        R.string.import_on,
        listOfNotNull(
            text(R.string.import_unmetered),
            text(R.string.import_metered).takeIf { c.meteredWifi },
            text(if (c.roaming) R.string.import_mobile_roaming else R.string.import_mobile).takeIf { c.mobileData },
        ).joined(", "),
    ),
    powerText(c.power).takeIf { c.power != PowerSource.Any },
    text(if (c.respectBatterySaver) R.string.import_battery_saver_on else R.string.import_battery_saver_off),
    text(if (c.startOnBoot) R.string.import_boot_on else R.string.import_boot_off),
    text(R.string.import_sync_times, if (c.timeSchedule) c.windows.map(::windowText).joined("; ") else text(R.string.import_any_time)),
    text(R.string.import_proxy, if (proxy.type == ProxyType.None) text(R.string.import_proxy_none) else proxyText(proxy)),
)

/** A device's short ID, and what it may add to this phone on its own. */
@Composable
private fun deviceImportText(d: ForkDevice): String = listOfNotNull(
    shortId(d.id),
    stringResource(R.string.import_device_introducer).takeIf { d.introducer },
    stringResource(R.string.import_device_auto_accept).takeIf { d.autoAccept },
).joinToString("\n")

/** A Fork setting this app has no equivalent for, or one the import took out, for people. */
fun notImportedText(n: ForkNotImported): UiText = when (n.key) {
    "networks" -> text(R.string.not_imported_networks)
    "force_start" -> text(R.string.not_imported_force_start)
    "force_stop" -> text(R.string.not_imported_force_stop)
    "flight_mode" -> text(R.string.not_imported_flight_mode)
    "master_sync" -> text(R.string.not_imported_master_sync)
    "wifi_allowlist" -> text(R.string.not_imported_wifi_allowlist)
    "schedule" -> text(R.string.not_imported_schedule)
    "broadcasts" -> text(R.string.not_imported_broadcasts)
    "socks_proxy" -> text(R.string.not_imported_socks_proxy)
    "http_proxy" -> text(R.string.not_imported_http_proxy)
    "environment" -> text(R.string.not_imported_environment)
    "folder_conditions" -> text(R.string.not_imported_folder_conditions, n.subject)
    "folder_script" -> text(R.string.not_imported_folder_script, n.subject)
    "device_conditions" -> text(R.string.not_imported_device_conditions, n.subject)
    "folder_id" -> text(R.string.not_imported_folder_id, n.subject)
    "folder_filesystem" -> text(R.string.not_imported_folder_filesystem, n.subject)
    "folder_path" -> text(R.string.not_imported_folder_path, n.subject)
    "folder_overlap" -> text(R.string.not_imported_folder_overlap, n.subject)
    "folder_versioning" -> text(R.string.not_imported_folder_versioning, n.subject)
    "folder_versions_path" -> text(R.string.not_imported_folder_versions_path, n.subject)
    "folder_marker" -> text(R.string.not_imported_folder_marker, n.subject)
    "default_folder_path" -> text(R.string.not_imported_default_folder_path)
    "default_folder_versioning" -> text(R.string.not_imported_default_folder_versioning)
    "default_folder_versions_path" -> text(R.string.not_imported_default_folder_versions_path)
    "default_folder_marker" -> text(R.string.not_imported_default_folder_marker)
    "device_addresses" -> text(R.string.not_imported_device_addresses, n.subject)
    "default_device_addresses" -> text(R.string.not_imported_default_device_addresses)
    "discovery_servers" -> text(R.string.not_imported_discovery_servers)
    "stun_servers" -> text(R.string.not_imported_stun_servers)
    "listen_addresses" -> text(R.string.not_imported_listen_addresses)
    "local_networks" -> text(R.string.not_imported_local_networks)
    "local_discovery" -> text(R.string.not_imported_local_discovery)
    "feature_flags" -> text(R.string.not_imported_feature_flags)
    // A key from a newer bridge: shown as it came.
    else -> raw(listOf(n.key, n.subject).filter { it.isNotEmpty() }.joinToString(": "))
}
