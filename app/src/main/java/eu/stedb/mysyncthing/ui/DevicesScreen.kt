package eu.stedb.mysyncthing.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Summary
import eu.stedb.mysyncthing.core.R as CoreR

@Composable
fun DevicesScreen(
    s: Snapshot,
    summary: Summary,
    vm: MainViewModel,
    onOpen: (String) -> Unit,
    onOpenThisPhone: () -> Unit,
    onAdd: (id: String, name: String) -> Unit,
) {
    var showMyId by rememberSaveable { mutableStateOf(false) }
    val live by vm.liveStats.collectAsStateWithLifecycle()
    AppScreen(
        title = stringResource(R.string.tab_devices),
        status = summary,
        fab = {
            AddFab(stringResource(R.string.devices_add)) { onAdd("", "") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
                item("me") { ThisDeviceCard(s, live, onOpenThisPhone) { showMyId = true } }
                s.pendingDevices.forEach { (id, p) ->
                    item("pending-$id") {
                        RequestCard(
                            stringResource(CoreR.string.request_device_title, p.name.ifEmpty { shortId(id) }),
                            stringResource(R.string.devices_request_detail, shortId(id), p.address),
                        ) {
                            TextButton(onClick = { vm.ignoreDevice(id) }) { Text(stringResource(CoreR.string.action_ignore)) }
                            TextButton(onClick = { onAdd(id, p.name) }) { Text(stringResource(CoreR.string.action_review)) }
                        }
                    }
                }
                grouped(s.devices) { it.group }.forEach { (group, devices) ->
                    if (group.isNotEmpty()) item("group-$group") { GroupTitle(group) }
                    items(devices, key = { it.deviceID }) { d -> DeviceCard(d, s, onPause = { vm.setDevicePaused(d.deviceID, it) }) { onOpen(d.deviceID) } }
                }
                if (s.loaded && s.devices.isEmpty() && s.pendingDevices.isEmpty()) {
                    item("empty") {
                        // The list scrolls already, so the hint itself must not (EmptyState would crash here).
                        EmptyHint(
                            Icons.Default.Devices, stringResource(R.string.devices_empty),
                            stringResource(R.string.devices_empty_hint),
                            Modifier.padding(horizontal = 24.dp, vertical = 48.dp),
                        )
                    }
                }
            }
        }
    }
    if (showMyId) MyIdDialog(s.myId) { showMyId = false }
}

/** This phone, in its own colour so it is never taken for one of the remote devices below. */
@Composable
private fun ThisDeviceCard(s: Snapshot, live: LiveStats?, onOpen: () -> Unit, onShowQr: () -> Unit) {
    val context = LocalContext.current
    val on = MaterialTheme.colorScheme.onSecondaryContainer
    // Tonal buttons are secondaryContainer too: on this card they take the other cards' colour.
    val buttons = ButtonDefaults.filledTonalButtonColors(containerColor = AppColors.card().containerColor, contentColor = on)
    Card(
        onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = on),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Smartphone, null, tint = on)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(s.myName.ifEmpty { stringResource(R.string.this_phone) }, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.devices_this_device_id, shortId(s.myId)), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(speedText(live?.total ?: Speed.Zero).asString(), style = MaterialTheme.typography.bodySmall)
            live?.let { l ->
                val look = l.health.look()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(look.icon, null, Modifier.size(16.dp), tint = look.color)
                    Spacer(Modifier.width(6.dp))
                    Text(l.healthText.asString(), style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(shape = ButtonShape, colors = buttons, onClick = onShowQr) {
                    Icon(Icons.Default.QrCode, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.devices_show_id))
                }
                val chooserTitle = stringResource(R.string.devices_share_id_title)
                FilledTonalButton(shape = ButtonShape, colors = buttons, onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.myId)
                    context.startActivity(Intent.createChooser(send, chooserTitle))
                }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.devices_share_id))
                }
            }
        }
    }
}

/**
 * A remote device: its status, why it is paused, and the user's own pause as a toggle. While the
 * run conditions pause everything the toggle is off limits; the device's screen still sets it.
 */
@Composable
private fun DeviceCard(d: Device, s: Snapshot, onPause: (Boolean) -> Unit, onClick: () -> Unit) {
    val conn = s.connections[d.deviceID]
    val pause = s.pause
    val status = deviceStatus(d, conn, s.completion[d.deviceID], pause)
    val look = status.state.look()
    val folders = s.folders.count { f -> f.devices.any { it.deviceID == d.deviceID } }
    val name = d.name.ifEmpty { shortId(d.deviceID) }
    val byUser = pause.byUser(d)
    ListCard(onClick = onClick) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(look.icon, null, Modifier.size(18.dp), tint = look.color)
                Spacer(Modifier.width(4.dp))
                Text(status.text.asString(), style = MaterialTheme.typography.labelLarge, color = look.color)
                // The same outline on and off: the icon (pause or play) says which it is.
                val enabled = !pause.blocked
                val on = MaterialTheme.colorScheme.onSurface
                OutlinedIconToggleButton(
                    checked = byUser, onCheckedChange = onPause, enabled = enabled, shape = ButtonShape,
                    colors = IconButtonDefaults.outlinedIconToggleButtonColors(
                        contentColor = on, checkedContentColor = on, checkedContainerColor = Color.Transparent,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else DISABLED_ALPHA)),
                ) {
                    val label = stringResource(if (byUser) R.string.device_resume_named else R.string.device_pause_named, name)
                    Icon(if (byUser) Icons.Default.PlayArrow else Icons.Default.Pause, label, Modifier.size(20.dp))
                }
            }
            Text(
                listOfNotNull(pauseReason(d, pause) ?: connectionText(conn, withAddress = false), count(R.plurals.count_folders, folders))
                    .map { it.asString() }.joinToString(" · ")
                    // A reason written to follow "Paused: " starts lower case in some languages (Italian).
                    .replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            status.progress?.let {
                ProgressBar(it, look.color, Modifier.padding(top = 6.dp, end = 8.dp))
            }
        }
    }
}

@Composable
private fun MyIdDialog(id: String, onDismiss: () -> Unit) {
    val qr = remember(id) { qrCode(id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.this_device)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(qr, stringResource(R.string.devices_qr_description), Modifier.size(240.dp))
                SelectionContainer { Text(id, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Material's opacity for a disabled control's outline. */
private const val DISABLED_ALPHA = 0.12f
