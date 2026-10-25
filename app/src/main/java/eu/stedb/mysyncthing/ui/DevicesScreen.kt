package eu.stedb.mysyncthing.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Summary

@Composable
fun DevicesScreen(
    s: Snapshot,
    summary: Summary,
    vm: MainViewModel,
    onOpen: (String) -> Unit,
    onOpenThisPhone: () -> Unit,
    review: String? = null,
    onReviewShown: () -> Unit = {},
) {
    // Pair of (id, name) for the add dialog; also used to accept a pending device.
    var draft by rememberSaveable { mutableStateOf<List<String>?>(null) }
    // A request notification's Review opens the add dialog for that device, if it is still waiting.
    LaunchedEffect(review, s.loaded) {
        if (review == null || !s.loaded) return@LaunchedEffect
        s.pendingDevices[review]?.let { draft = listOf(review, it.name) }
        onReviewShown()
    }
    var showMyId by rememberSaveable { mutableStateOf(false) }
    val live by remember(vm) { vm.liveStats() }.collectAsStateWithLifecycle(null)
    AppScreen(
        title = "Devices",
        actions = { StatusChip(summary) },
        fab = {
            ExtendedFloatingActionButton(onClick = { draft = listOf("", "") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add device") })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                item("me") { ThisDeviceCard(s, live, onOpenThisPhone) { showMyId = true } }
                s.pendingDevices.forEach { (id, p) ->
                    item("pending-$id") {
                        RequestCard("${p.name.ifEmpty { shortId(id) }} wants to connect", "Device ID ${shortId(id)} · ${p.address}") {
                            TextButton(onClick = { vm.ignoreDevice(id) }) { Text("Ignore") }
                            TextButton(onClick = { draft = listOf(id, p.name) }) { Text("Review") }
                        }
                    }
                }
                items(s.devices, key = { it.deviceID }) { d -> DeviceCard(d, s) { onOpen(d.deviceID) } }
                if (s.loaded && s.devices.isEmpty() && s.pendingDevices.isEmpty()) {
                    item("empty") {
                        EmptyState(
                            Icons.Default.Devices, "No other devices yet",
                            "Tap Add device and scan the other device's QR code, or show it this phone's ID.",
                        )
                    }
                }
            }
        }
    }
    draft?.let { (id, name) ->
        DeviceDialog(id, name, onDismiss = { draft = null }) { newId, newName -> vm.addDevice(newId, newName); draft = null }
    }
    if (showMyId) MyIdDialog(s.myId) { showMyId = false }
}

@Composable
private fun ThisDeviceCard(s: Snapshot, live: LiveStats?, onOpen: () -> Unit, onShowQr: () -> Unit) {
    val context = LocalContext.current
    ListCard(onClick = onOpen) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Smartphone, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(s.myName.ifEmpty { "This phone" }, style = MaterialTheme.typography.titleMedium)
                    Text("This device · ID ${shortId(s.myId)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            live?.let { l ->
                l.total?.let { Text(speedText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val look = l.health.look()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(look.icon, null, Modifier.size(16.dp), tint = look.color)
                    Spacer(Modifier.width(6.dp))
                    Text(l.healthText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(shape = ButtonShape, onClick = onShowQr) {
                    Icon(Icons.Default.QrCode, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Show ID")
                }
                FilledTonalButton(shape = ButtonShape, onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.myId)
                    context.startActivity(Intent.createChooser(send, "Share device ID"))
                }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share ID")
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(d: Device, s: Snapshot, onClick: () -> Unit) {
    val conn = s.connections[d.deviceID]
    val status = deviceStatus(d, conn, s.completion[d.deviceID])
    val look = status.state.look()
    val folders = s.folders.count { f -> f.devices.any { it.deviceID == d.deviceID } }
    ListCard(onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.name.ifEmpty { shortId(d.deviceID) }, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(look.icon, null, Modifier.size(18.dp), tint = look.color)
                Spacer(Modifier.width(4.dp))
                Text(status.text, style = MaterialTheme.typography.labelLarge, color = look.color)
            }
            Text(
                listOfNotNull(connectionText(conn), "$folders ${if (folders == 1) "folder" else "folders"}").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.progress?.let {
                ProgressBar(it, look.color, Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun DeviceDialog(initialId: String, initialName: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var id by remember { mutableStateOf(initialId) }
    var name by remember { mutableStateOf(initialName) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { id = it.trim() } }
    val valid = DEVICE_ID.matches(id.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    id, { id = it }, label = { Text("Device ID") }, readOnly = initialId.isNotEmpty(),
                    isError = id.isNotBlank() && !valid,
                    supportingText = { Text(if (initialId.isNotEmpty()) "Check it matches the other device" else "Scan its QR code or paste it") },
                    trailingIcon = {
                        if (initialId.isEmpty()) IconButton(onClick = {
                            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false)
                                .setCaptureActivity(ScanActivity::class.java))
                        }) { Icon(Icons.Default.QrCodeScanner, "Scan QR code") }
                    },
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(id, name) }, enabled = valid) { Text("Add") } },
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
