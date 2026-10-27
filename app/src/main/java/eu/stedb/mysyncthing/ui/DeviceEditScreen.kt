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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.stedb.mysyncthing.R
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
        title = if (isRequest) stringResource(R.string.device_add_named, initialName.ifEmpty { shortId(initialId) }) else stringResource(R.string.devices_add),
        onBack = back,
        actions = {
            TextButton(enabled = valid, onClick = { onSave(canonical!!, name, s.folders.filter { it.id in share }) }) { Text(stringResource(R.string.action_save)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            s.pendingDevices[initialId]?.address?.ifEmpty { null }?.let {
                Text(
                    stringResource(R.string.device_wants_to_connect, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
            SettingsGroup(stringResource(R.string.device)) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        id, { id = it }, label = { Text(stringResource(R.string.device_id)) }, readOnly = isRequest,
                        isError = id.isNotBlank() && !valid,
                        supportingText = {
                            Text(
                                when {
                                    known -> stringResource(R.string.folder_already_added)
                                    id.isNotBlank() && canonical == null -> deviceIdError(id)!!.asString()
                                    isRequest -> stringResource(R.string.device_id_check)
                                    else -> stringResource(R.string.device_id_hint)
                                },
                            )
                        },
                        trailingIcon = {
                            if (!isRequest) IconButton(onClick = {
                                scan.launch(
                                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false)
                                        .setCaptureActivity(ScanActivity::class.java),
                                )
                            }) { Icon(Icons.Default.QrCodeScanner, stringResource(R.string.scan_qr_code)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        name, { name = it }, label = { Text(stringResource(R.string.name_optional)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            if (candidates.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.device_nearby)) {
                    candidates.forEach { (device, entry) ->
                        SettingRow(
                            shortId(device), entry.addresses.firstOrNull()?.substringAfter("://") ?: stringResource(R.string.device_nearby_tap), icon = Icons.Default.Devices,
                        ) { id = device }
                    }
                }
            }
            SettingsGroup(stringResource(R.string.device_share_folders)) {
                if (s.folders.isEmpty()) SettingRow(stringResource(R.string.folders_empty), stringResource(R.string.device_no_folders_hint))
                s.folders.forEach { f ->
                    val on = f.id in share
                    CheckRow(f.label.ifEmpty { f.id }, displayPath(f.path).asString(), on) { share = if (it) share + f.id else share - f.id }
                }
            }
        }
    }
}
