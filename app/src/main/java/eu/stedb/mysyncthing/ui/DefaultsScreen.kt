package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.Versioning
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private enum class DefaultsDialog { Type, Rescan, Order, DiskFree, Addresses, Recv, Send, Compression, Connections }

/**
 * What every new folder starts with: the folder screen's settings without
 * status, sharing and Remove. Folders accepted automatically use them too.
 */
@Composable
fun DefaultFolderScreen(vm: MainViewModel, onBack: () -> Unit, onOpenIgnores: () -> Unit) {
    var f by remember { mutableStateOf<Folder?>(null) }
    var ignores by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) {
        f = vm.defaultFolder()
        ignores = vm.defaultIgnores()
    }
    var dialog by rememberSaveable { mutableStateOf<DefaultsDialog?>(null) }
    val scope = rememberCoroutineScope()
    // Shows the change at once; on failure rereads what the core has.
    val change = { new: Folder, label: String, fields: JsonObjectBuilder.() -> Unit ->
        f = new
        scope.launch { if (!vm.patchDefaults(device = false, label, fields)) f = vm.defaultFolder() }
        Unit
    }

    AppScreen(title = "Default settings for new folders", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            val d = f
            if (d == null) {
                Intro("Loading")
                return@Column
            }
            Intro("Folders you add, and folders you accept from other devices, start with these settings.")
            SettingsGroup("General") {
                SettingRow("Type", "${folderTypeLabel(d.type)}. ${folderTypeHint(d.type).orEmpty()}") { dialog = DefaultsDialog.Type }
                SettingRow("Location", "${displayPath(d.path)}/<folder name>")
            }
            SettingsGroup("File versioning") {
                VersioningEditor(d.versioning) { v -> change(d.copy(versioning = v), "Versioning") { putVersioning(v) } }
            }
            SettingsGroup("Advanced") {
                SwitchRow("Watch for changes", "Sync edits within seconds instead of at the next scan", d.fsWatcherEnabled) { v ->
                    change(d.copy(fsWatcherEnabled = v), "Watch for changes") { put("fsWatcherEnabled", v) }
                }
                SettingRow("Full rescan", rescanText(d.rescanIntervalS)) { dialog = DefaultsDialog.Rescan }
                SettingRow("Download order", orderLabel(d.order)) { dialog = DefaultsDialog.Order }
                SettingRow("Keep free on this phone", diskSizeText(d.minDiskFree)) { dialog = DefaultsDialog.DiskFree }
                SettingRow(
                    "Ignore patterns", ignores?.let { if (it.isEmpty()) "None" else count(it.size, "rule") } ?: "Loading",
                    onClick = onOpenIgnores,
                )
            }
        }
    }

    val d = f ?: return
    val close = { dialog = null }
    when (dialog) {
        DefaultsDialog.Type -> ChoiceDialog("Folder type", FOLDER_TYPES, d.type, ::folderTypeHint, onDismiss = close) { t ->
            close(); change(d.copy(type = t), "Type") { put("type", t) }
        }
        DefaultsDialog.Rescan -> ChoiceDialog("Full rescan", rescanChoices(d.rescanIntervalS), d.rescanIntervalS, ::rescanHint, onDismiss = close) { n ->
            close(); change(d.copy(rescanIntervalS = n), "Full rescan") { put("rescanIntervalS", n) }
        }
        DefaultsDialog.Order -> ChoiceDialog("Download order", PULL_ORDERS, d.order, onDismiss = close) { o ->
            close(); change(d.copy(order = o), "Download order") { put("order", o) }
        }
        DefaultsDialog.DiskFree -> DiskFreeDialog(d.minDiskFree, INTERNAL_STORAGE, onDismiss = close) { size ->
            close()
            change(d.copy(minDiskFree = size), "Keep free") { putJsonObject("minDiskFree") { put("value", size.value); put("unit", size.unit) } }
        }
        else -> {}
    }
}

/** What every new device starts with: the device screen's settings without status, ID and Remove. */
@Composable
fun DefaultDeviceScreen(vm: MainViewModel, onBack: () -> Unit) {
    var dev by remember { mutableStateOf<Device?>(null) }
    LaunchedEffect(Unit) { dev = vm.defaultDevice() }
    var dialog by rememberSaveable { mutableStateOf<DefaultsDialog?>(null) }
    val scope = rememberCoroutineScope()
    val change = { new: Device, label: String, fields: JsonObjectBuilder.() -> Unit ->
        dev = new
        scope.launch { if (!vm.patchDefaults(device = true, label, fields)) dev = vm.defaultDevice() }
        Unit
    }

    AppScreen(title = "Default settings for new devices", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            val d = dev
            if (d == null) {
                Intro("Loading")
                return@Column
            }
            Intro("Devices you add, and device requests you accept, start with these settings.")
            SettingsGroup("Trust") {
                SwitchRow("Introducer", "Also add the devices it shares folders with", d.introducer) { v ->
                    change(d.copy(introducer = v), "Introducer") { put("introducer", v) }
                }
                SwitchRow("Accept its folders automatically", "Saved to Syncthing/<folder name> without asking", d.autoAcceptFolders) { v ->
                    change(d.copy(autoAcceptFolders = v), "Accept folders") { put("autoAcceptFolders", v) }
                }
            }
            SettingsGroup("Advanced") {
                SettingRow("Addresses", addressesText(d.addresses)) { dialog = DefaultsDialog.Addresses }
                SettingRow("Download limit", limitText(d.maxRecvKbps)) { dialog = DefaultsDialog.Recv }
                SettingRow("Upload limit", limitText(d.maxSendKbps)) { dialog = DefaultsDialog.Send }
                SettingRow("Compression", COMPRESSIONS.find { it.first == d.compression }?.second ?: d.compression) { dialog = DefaultsDialog.Compression }
                SettingRow("Connections", connectionsText(d.numConnections)) { dialog = DefaultsDialog.Connections }
            }
        }
    }

    val d = dev ?: return
    val close = { dialog = null }
    when (dialog) {
        DefaultsDialog.Addresses -> AddressesDialog(d.addresses, onDismiss = close) { list ->
            close()
            change(d.copy(addresses = list), "Addresses") { putJsonArray("addresses") { list.forEach { add(JsonPrimitive(it)) } } }
        }
        DefaultsDialog.Recv -> SpeedLimitDialog("Download limit", d.maxRecvKbps, onDismiss = close) { kib ->
            close(); change(d.copy(maxRecvKbps = kib), "Download limit") { put("maxRecvKbps", kib) }
        }
        DefaultsDialog.Send -> SpeedLimitDialog("Upload limit", d.maxSendKbps, onDismiss = close) { kib ->
            close(); change(d.copy(maxSendKbps = kib), "Upload limit") { put("maxSendKbps", kib) }
        }
        DefaultsDialog.Compression -> ChoiceDialog("Compression", COMPRESSIONS, d.compression, ::compressionHint, onDismiss = close) { c ->
            close(); change(d.copy(compression = c), "Compression") { put("compression", c) }
        }
        DefaultsDialog.Connections -> ChoiceDialog(
            "Connections", connectionChoices(d.numConnections), d.numConnections.coerceAtLeast(0), onDismiss = close,
        ) { n -> close(); change(d.copy(numConnections = n), "Connections") { put("numConnections", n) } }
        else -> {}
    }
}

@Composable
private fun Intro(text: String) = Text(
    text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
)

private fun JsonObjectBuilder.putVersioning(v: Versioning) = putJsonObject("versioning") {
    put("type", v.type)
    putJsonObject("params") { v.params.forEach { (k, value) -> put(k, value) } }
}
