package eu.stedb.mysyncthing.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import eu.stedb.mysyncthing.core.DevicePause
import eu.stedb.mysyncthing.core.NEVER_SYNCS
import eu.stedb.mysyncthing.core.RunConditions
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
    onOpenSchedule: () -> Unit,
    onAdd: (id: String, name: String) -> Unit,
) {
    var showMyId by rememberSaveable { mutableStateOf(false) }
    val live by vm.liveStats.collectAsStateWithLifecycle()
    val rc by vm.runConditions.collectAsStateWithLifecycle()
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
                item("all") {
                    AllDevices(rc, s.pause, onPause = { v -> vm.setRunConditions { it.copy(pausedByUser = v) } }, onOpenSchedule = onOpenSchedule)
                }
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
 * run conditions or its own windows hold it the toggle is off limits and the line says why (that
 * reason wins over "By you"); the device's screen still sets the user's pause.
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
                val enabled = !pause.blocked(d)
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
            val reasonText = pauseReason(d, pause)
            val reason = reasonText?.asString()
            val where = connectionText(conn, withAddress = false)?.asString()
                ?: lastSeenText(s.lastSeen[d.deviceID]).asString().let { if (reason != null) it.replaceFirstChar { c -> c.lowercase() } else it }
            Text(
                listOfNotNull(reason, where, count(R.plurals.count_folders, folders).asString()).joinToString(" · ")
                    // A reason written to follow "Paused: " starts lower case in some languages (Italian).
                    .replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.bodySmall,
                // Never syncing is a mistake to fix, not a state to wait out.
                color = if (reasonText == NEVER_SYNCS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            status.progress?.let {
                ProgressBar(it, look.color, Modifier.padding(top = 6.dp, end = 8.dp))
            }
        }
    }
}

/**
 * The controls that apply to every device: the user's pause, a toggle, and the sync times,
 * which open their screen. Each shows its value, the default marked; the one that pauses
 * the devices now is in amber.
 */
@Composable
private fun AllDevices(rc: RunConditions, pause: DevicePause, onPause: (Boolean) -> Unit, onOpenSchedule: () -> Unit) {
    GroupTitle(stringResource(R.string.devices_all))
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val paused = rc.pausedByUser
        ControlCard(
            stringResource(R.string.devices_pause_all),
            if (paused) stringResource(R.string.devices_pause_on) else stringResource(R.string.value_default, stringResource(R.string.devices_pause_off)),
            active = paused,
            Modifier.weight(1f).fillMaxHeight().testTag("pauseAll").toggleable(paused, role = Role.Switch, onValueChange = onPause),
        ) {
            // A smaller switch, so the label keeps one line in half the width; it only draws the card's state.
            Box(Modifier.size(42.dp, 26.dp), contentAlignment = Alignment.Center) {
                Switch(paused, onCheckedChange = null, modifier = Modifier.wrapContentSize(unbounded = true).scale(0.8f))
            }
        }
        // Half the width: at the largest font a time range may not fit a line.
        val times = breakableRanges(scheduleText(rc).asString())
        ControlCard(
            stringResource(R.string.devices_sync_times),
            if (rc.timeSchedule) times else stringResource(R.string.value_default, times),
            active = scheduleBlocks(rc, pause),
            Modifier.weight(1f).fillMaxHeight().testTag("syncTimes").clickable(onClick = onOpenSchedule),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Half of the [AllDevices] row: a title, its value and a trailing mark; the modifier makes it a toggle or a button. */
@Composable
private fun ControlCard(title: String, value: String, active: Boolean, modifier: Modifier, trailing: @Composable () -> Unit) {
    val base = AppColors.card().containerColor
    val amber = AppColors.busy
    Surface(
        modifier = modifier, shape = MaterialTheme.shapes.extraSmall,
        color = if (active) amber.copy(alpha = ACTIVE_ALPHA).compositeOver(base) else base,
        border = if (active) BorderStroke(1.dp, amber) else AppColors.cardBorder(),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    value, style = MaterialTheme.typography.bodySmall,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Medium else null,
                )
            }
            Spacer(Modifier.width(4.dp))
            trailing()
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

/** The amber tint of a control that pauses the devices now. */
private const val ACTIVE_ALPHA = 0.14f
