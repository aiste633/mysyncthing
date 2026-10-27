package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.ServiceStatus
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.SyncthingCore

/** This phone's traffic and whether other devices can find and reach it (web GUI: "This Device"). */
@Composable
fun ThisPhoneScreen(s: Snapshot, vm: MainViewModel, onBack: () -> Unit) {
    val live by remember(vm) { vm.liveStats() }.collectAsStateWithLifecycle(null)
    AppScreen(title = s.myName.ifEmpty { stringResource(R.string.this_phone) }, onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ListCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.devices_this_device_id, shortId(s.myId)), style = MaterialTheme.typography.titleMedium)
                    val l = live
                    if (l == null) {
                        Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val muted = MaterialTheme.colorScheme.onSurfaceVariant
                        l.total?.let { Text(speedText(it).asString(), style = MaterialTheme.typography.bodyMedium, color = muted) }
                        Text(
                            stringResource(R.string.this_phone_traffic, bytes(l.connections.total.inBytesTotal), bytes(l.connections.total.outBytesTotal)),
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                        Text(
                            stringResource(R.string.this_phone_uptime, duration(l.status.uptime), SyncthingCore.version),
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                }
            }
            live?.let { l ->
                SettingsGroup(stringResource(R.string.this_phone_listening)) {
                    if (l.listeners.isEmpty()) SettingRow(stringResource(R.string.this_phone_no_listeners), stringResource(R.string.this_phone_no_listeners_hint))
                    l.listeners.toSortedMap().forEach { (address, st) -> ServiceRow(address, st) }
                }
                SettingsGroup(stringResource(R.string.this_phone_discovery)) {
                    if (!l.status.discoveryEnabled) SettingRow(stringResource(R.string.this_phone_discovery_off), stringResource(R.string.this_phone_discovery_off_hint))
                    l.discovery.toSortedMap().forEach { (method, st) -> ServiceRow(method, st, unusedHere(st)?.asString()) }
                }
            }
        }
    }
}

@Composable
private fun ServiceRow(name: String, st: ServiceStatus, unused: String? = null) {
    // Grey: nothing to fix while on this network.
    val (icon, tint) = when {
        st.error == null -> SyncState.UpToDate.look().let { it.icon to it.color }
        unused != null -> Icons.Default.RemoveCircleOutline to AppColors.neutral
        else -> SyncState.Problem.look().let { it.icon to it.color }
    }
    SettingRow(name, unused ?: st.error ?: stringResource(R.string.this_phone_working), trailing = { Icon(icon, null, Modifier.size(20.dp), tint = tint) })
}
