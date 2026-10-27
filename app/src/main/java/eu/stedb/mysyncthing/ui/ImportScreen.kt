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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stedb.mysyncthing.core.ForkFolder
import eu.stedb.mysyncthing.core.ForkImport
import eu.stedb.mysyncthing.core.ForkNotImported
import eu.stedb.mysyncthing.core.ForkPreview
import eu.stedb.mysyncthing.core.PowerSource
import eu.stedb.mysyncthing.core.ProxySettings
import eu.stedb.mysyncthing.core.ProxyType
import eu.stedb.mysyncthing.core.RunConditions
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

    AppScreen("Import backup", onBack = if (working) null else back) { padding ->
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
                    Text(s.what)
                }
                is ImportStep.Done -> Note(
                    if (s.problem == null) "Imported" else "Import finished with a problem",
                    listOfNotNull(
                        s.problem,
                        "Syncthing restarted with the imported setup and is checking every folder again. " +
                            "Identical files are recognised and not transferred.",
                        if (s.keepIdentity) "This phone now has the backup's device ID: stop or uninstall Syncthing " +
                            "where the backup came from, or both will disturb each other's connections." else null,
                    ).joinToString("\n\n"),
                )
                is ImportStep.Failed -> {
                    Note("Cannot import this file", s.message)
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        OutlinedButton(shape = ButtonShape, onClick = vm::restart) { Text("Choose another file") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChooseStep(onFound: (File) -> Unit, onPick: () -> Unit) {
    val found = remember { ForkImport.defaultBackup.takeIf { it.isFile } }
    Note(
        "Bring your setup over",
        "Choose a backup exported by this app (Settings > Export a backup), or by Syncthing-Fork (Settings > Import and Export). " +
            "This phone then gets its folders, devices and settings. The files in the folders are not part of the backup.",
    )
    if (found != null) {
        SettingsGroup("Backup found") {
            SettingRow(
                wrapPath(displayPath(found.path)),
                "${dateTimeText(found.lastModified())}, ${bytes(found.length())}",
                Icons.Default.Folder,
            ) { onFound(found) }
        }
    }
    Row(Modifier.padding(16.dp)) {
        Button(shape = ButtonShape, onClick = onPick) {
            Icon(Icons.Default.FolderOpen, null)
            Text("Choose a backup file", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun PasswordStep(tried: String, wrong: Boolean, password: String, onChange: (String) -> Unit, onContinue: () -> Unit) {
    // The text itself stays in the ViewModel: saved state must not hold it.
    var show by rememberSaveable { mutableStateOf(false) }
    var edited by rememberSaveable { mutableStateOf(false) }
    val problem = backupPasswordError(password, wrong && password == tried)
    Note("Password protected", "Enter the password the backup was exported with.")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            password, { onChange(it); edited = true },
            label = { Text("Backup password") }, singleLine = true,
            isError = problem != null && (edited || wrong),
            supportingText = problem?.takeIf { edited || wrong }?.let { { Text(it) } },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide password" else "Show password")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(shape = ButtonShape, enabled = backupPasswordError(password, false) == null, onClick = onContinue) {
            Text("Continue")
        }
    }
}

@Composable
private fun ReviewStep(p: ForkPreview, onImport: (keepIdentity: Boolean, deleteBackup: Boolean) -> Unit) {
    var keepIdentity by rememberSaveable { mutableStateOf(true) }
    var deleteBackup by rememberSaveable { mutableStateOf(true) }
    var confirm by rememberSaveable { mutableStateOf(false) }

    SettingsGroup("Device ID") {
        RadioRow(
            "Take over \"${p.deviceName.ifEmpty { shortId(p.deviceID) }}\" (${shortId(p.deviceID)})",
            "Other devices keep syncing with this phone as before. " +
                (if (p.source == "own") "The phone the backup came from" else "Syncthing-Fork") + " must not run under this ID any more.",
            keepIdentity,
        ) { keepIdentity = true }
        RadioRow(
            "Keep this phone's own ID",
            "Other devices must accept this phone as a new device. The old install can keep running.",
            !keepIdentity,
        ) { keepIdentity = false }
    }
    SettingsGroup(count(p.folders.size, "folder")) {
        p.folders.forEach { f -> SettingRow(f.label.ifEmpty { f.id }, folderImportText(f), Icons.Default.Folder) }
    }
    SettingsGroup(count(p.devices.size, "device")) {
        p.devices.forEach { d -> SettingRow(d.name.ifEmpty { shortId(d.id) }, shortId(d.id), Icons.Default.Devices) }
    }
    SettingsGroup("Sync settings") {
        conditionsText(p.conditions, p.proxy).forEach { SettingRow(it) }
    }
    if (p.notImported.isNotEmpty()) {
        SettingsGroup("Not imported") {
            p.notImported.forEach { SettingRow(notImportedText(it)) }
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
                "The backup holds the device's private key. Any app that can read the file can pose as this device.",
                color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 12.dp),
            )
        }
        CheckRow("Delete the backup file after importing", "Recommended", deleteBackup) { deleteBackup = it }
    }
    Row(Modifier.padding(horizontal = 16.dp)) {
        Button(shape = ButtonShape, onClick = { confirm = true }) { Text("Import") }
    }

    if (confirm) {
        ConfirmDialog(
            "Replace this phone's setup?",
            "The folders, devices and settings of Syncthing on this phone are replaced by the backup's. " +
                "Files on the phone are not touched.",
            "Replace", onDismiss = { confirm = false },
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
fun backupPasswordError(password: String, wrong: Boolean): String? = when {
    wrong -> "Wrong password"
    password.isEmpty() -> "Enter the backup's password"
    password.length > MAX_BACKUP_PASSWORD -> "At most $MAX_BACKUP_PASSWORD characters"
    else -> null
}

/** What happens to an imported folder on this phone (design: the core creates what is missing). */
fun folderImportText(f: ForkFolder): String {
    val path = wrapPath(displayPath(f.path))
    return when {
        f.exists && f.marker -> "$path: found, will be checked again"
        f.exists -> "$path: found, will become a Syncthing folder and be checked"
        f.type == "sendonly" -> "$path: not on this phone, will be created empty"
        else -> "$path: not on this phone, will be created and filled from other devices"
    }
}

fun conditionsText(c: RunConditions, proxy: ProxySettings): List<String> = listOfNotNull(
    "On " + listOfNotNull(
        "unmetered Wi-Fi",
        "metered Wi-Fi".takeIf { c.meteredWifi },
        (if (c.roaming) "mobile data, also roaming" else "mobile data").takeIf { c.mobileData },
    ).joinToString(", "),
    powerText(c.power).takeIf { c.power != PowerSource.Any },
    if (c.respectBatterySaver) "Pause in battery saver: on" else "Pause in battery saver: off",
    if (c.startOnBoot) "Start at boot: on" else "Start at boot: off",
    "Sync times: " + if (c.timeSchedule) c.windows.joinToString("; ", transform = ::windowText) else "any time",
    "Proxy: " + if (proxy.type == ProxyType.None) "none" else proxyText(proxy),
)

/** A Fork setting this app has no equivalent for, for people. */
fun notImportedText(n: ForkNotImported): String = when (n.key) {
    "networks" -> "Not syncing on Wi-Fi: here unmetered Wi-Fi is always used, metered Wi-Fi is off"
    "force_start" -> "Force start (sync regardless of conditions)"
    "force_stop" -> "Syncing paused by hand (here only this phone's own pause counts)"
    "flight_mode" -> "Sync in flight mode"
    "master_sync" -> "Follow Android's auto-sync switch"
    "wifi_allowlist" -> "Sync only on chosen Wi-Fi networks"
    "schedule" -> "Sync for some minutes every few hours (here: Sync times, by the clock)"
    "broadcasts" -> "Control by other apps"
    "socks_proxy" -> "SOCKS proxy: not a valid address, or Tor was on"
    "http_proxy" -> "HTTP proxy: not a valid address, or another proxy was on"
    "environment" -> "Environment variables for Syncthing"
    "folder_conditions" -> "Own sync conditions of folder ${n.subject}"
    "folder_script" -> "Script after syncing folder ${n.subject}"
    "device_conditions" -> "Own sync conditions of device ${n.subject}"
    else -> listOf(n.key, n.subject).filter { it.isNotEmpty() }.joinToString(": ")
}
