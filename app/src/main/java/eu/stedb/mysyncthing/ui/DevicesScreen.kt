package eu.stedb.mysyncthing.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.Snapshot

private data class DeviceDraft(val id: String = "", val name: String = "")

@Composable
fun DevicesScreen(s: Snapshot, vm: MainViewModel) {
    var draft by remember { mutableStateOf<DeviceDraft?>(null) }
    var showMyId by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item("me") {
                ListItem(
                    modifier = Modifier.clickable { showMyId = true },
                    headlineContent = { Text("This device") },
                    supportingContent = { Text(shortId(s.myId)) },
                    trailingContent = { Icon(Icons.Default.QrCode, "Show device ID") },
                )
            }
            s.pendingDevices.forEach { (id, p) ->
                item("pending-$id") {
                    Card(Modifier.fillMaxWidth().padding(8.dp)) {
                        ListItem(
                            headlineContent = { Text("${p.name.ifEmpty { shortId(id) }} wants to connect") },
                            supportingContent = { Text(p.address) },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = { vm.dismissPendingDevice(id) }) { Text("Ignore") }
                                    TextButton(onClick = { draft = DeviceDraft(id, p.name) }) { Text("Add") }
                                }
                            },
                        )
                    }
                }
            }
            items(s.devices, key = { it.deviceID }) { d -> DeviceRow(d, s, vm) }
            if (s.loaded && s.devices.isEmpty() && s.pendingDevices.isEmpty()) {
                item("empty") {
                    EmptyState(
                        Icons.Default.Devices, "No other devices yet",
                        "Tap + and scan the other device's QR code, or show it this phone's ID from the row above.",
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { draft = DeviceDraft() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add device") }
    }
    draft?.let { d ->
        DeviceDialog(d, onDismiss = { draft = null }) { id, name -> vm.addDevice(id, name); draft = null }
    }
    if (showMyId) MyIdDialog(s.myId) { showMyId = false }
}

@Composable
private fun DeviceRow(d: Device, s: Snapshot, vm: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    val conn = s.connections[d.deviceID]
    val completion = s.completion[d.deviceID]
    val status = when {
        d.paused -> "Paused"
        conn?.connected != true -> "Disconnected"
        completion != null && completion.needBytes > 0 -> "Syncing ${completion.completion.toInt()} %"
        else -> "Up to date"
    }
    Box {
        ListItem(
            modifier = Modifier.clickable { menu = true },
            headlineContent = { Text(d.name.ifEmpty { shortId(d.deviceID) }) },
            supportingContent = { Text(listOfNotNull(status, conn?.address?.ifEmpty { null }).joinToString(" · ")) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (d.paused) "Resume" else "Pause") }, onClick = { vm.setDevicePaused(d.deviceID, !d.paused); menu = false })
            DropdownMenuItem(text = { Text("Remove") }, onClick = { vm.removeDevice(d.deviceID); menu = false })
        }
    }
}

@Composable
private fun DeviceDialog(initial: DeviceDraft, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var id by remember { mutableStateOf(initial.id) }
    var name by remember { mutableStateOf(initial.name) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { id = it.trim() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add device") },
        text = {
            Column {
                OutlinedTextField(
                    id, { id = it }, label = { Text("Device ID") },
                    trailingIcon = {
                        IconButton(onClick = {
                            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false))
                        }) { Icon(Icons.Default.QrCodeScanner, "Scan QR code") }
                    },
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(id, name) }, enabled = DEVICE_ID.matches(id.trim())) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MyIdDialog(id: String, onDismiss: () -> Unit) {
    val qr = remember(id) { qrCode(id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This device") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(qr, "Device ID QR code", Modifier.size(240.dp))
                SelectionContainer { Text(id, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** 8 groups of 7 base32 chars, dashes optional (Syncthing accepts both). */
private val DEVICE_ID = Regex("^[A-Za-z2-7]{7}(-?[A-Za-z2-7]{7}){7}$")
