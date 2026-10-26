package eu.stedb.mysyncthing.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.stedb.mysyncthing.core.Discovered
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.Snapshot
import kotlinx.coroutines.delay

/**
 * Full-screen form for adding a device or accepting a request, with the
 * folders to share. A request keeps its ID, which the user checks against
 * the other device.
 */
@Composable
fun DeviceEditScreen(
    s: Snapshot,
    initialId: String,
    initialName: String,
    vm: MainViewModel,
    onBack: () -> Unit,
    onSave: (id: String, name: String, folders: List<Folder>) -> Unit,
) {
    var id by rememberSaveable { mutableStateOf(initialId) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var share by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val isRequest = initialId.isNotEmpty()
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { id = it.trim() } }
    val canonical = deviceId(id)
    val known = canonical != null && (s.devices.any { it.deviceID == canonical } || s.myId == canonical)
    val valid = canonical != null && !known

    // Local discovery announces every 30 s, so a slow refresh is enough.
    var nearby by remember { mutableStateOf<Map<String, Discovered>>(emptyMap()) }
    if (!isRequest) LaunchedEffect(Unit) {
        while (true) {
            nearby = vm.discovery()
            delay(10_000)
        }
    }
    val added = remember(s.devices, s.myId) { (s.devices.map { it.deviceID } + s.myId).toSet() }
    val candidates = nearby.filterKeys { it !in added }

    val back = rememberDiscardGuard(id != initialId || name != initialName || share.isNotEmpty(), onBack)

    AppScreen(
        title = if (isRequest) "Add \"${initialName.ifEmpty { shortId(initialId) }}\"" else "Add device",
        onBack = back,
        actions = {
            TextButton(enabled = valid, onClick = { onSave(canonical!!, name, s.folders.filter { it.id in share }) }) { Text("Save") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            s.pendingDevices[initialId]?.address?.ifEmpty { null }?.let {
                Text(
                    "Wants to connect from $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
            SettingsGroup("Device") {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        id, { id = it }, label = { Text("Device ID") }, readOnly = isRequest,
                        isError = id.isNotBlank() && !valid,
                        supportingText = {
                            Text(
                                when {
                                    known -> "Already added"
                                    id.isNotBlank() && canonical == null -> deviceIdError(id)!!
                                    isRequest -> "Check it matches the other device"
                                    else -> "Scan its QR code, paste it, or pick a nearby device"
                                },
                            )
                        },
                        trailingIcon = {
                            if (!isRequest) IconButton(onClick = {
                                scan.launch(
                                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false)
                                        .setCaptureActivity(ScanActivity::class.java),
                                )
                            }) { Icon(Icons.Default.QrCodeScanner, "Scan QR code") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        name, { name = it }, label = { Text("Name (optional)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            if (candidates.isNotEmpty()) {
                SettingsGroup("Nearby on this network") {
                    candidates.forEach { (device, entry) ->
                        SettingRow(
                            shortId(device), entry.addresses.firstOrNull()?.substringAfter("://") ?: "Tap to use", icon = Icons.Default.Devices,
                        ) { id = device }
                    }
                }
            }
            SettingsGroup("Share folders") {
                if (s.folders.isEmpty()) SettingRow("No folders yet", "Add one on the Folders tab")
                s.folders.forEach { f ->
                    val on = f.id in share
                    CheckRow(f.label.ifEmpty { f.id }, displayPath(f.path), on) { share = if (it) share + f.id else share - f.id }
                }
            }
        }
    }
}
