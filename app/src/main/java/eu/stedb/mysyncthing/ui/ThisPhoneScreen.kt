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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.core.ServiceStatus
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.SyncthingCore

/** This phone's traffic and whether other devices can find and reach it (web GUI: "This Device"). */
@Composable
fun ThisPhoneScreen(s: Snapshot, vm: MainViewModel, onBack: () -> Unit) {
    val live by remember(vm) { vm.liveStats() }.collectAsStateWithLifecycle(null)
    AppScreen(title = s.myName.ifEmpty { "This phone" }, onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ListCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This device · ID ${shortId(s.myId)}", style = MaterialTheme.typography.titleMedium)
                    val l = live
                    if (l == null) {
                        Text("Loading", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val muted = MaterialTheme.colorScheme.onSurfaceVariant
                        l.total?.let { Text(speedText(it), style = MaterialTheme.typography.bodyMedium, color = muted) }
                        Text(
                            "Received ${bytes(l.connections.total.inBytesTotal)} · sent ${bytes(l.connections.total.outBytesTotal)} since start",
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                        Text(
                            "Running for ${duration(l.status.uptime)} · Syncthing ${SyncthingCore.version}",
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                }
            }
            live?.let { l ->
                SettingsGroup("Listening for connections") {
                    if (l.listeners.isEmpty()) SettingRow("No listen addresses", "Other devices can only connect through relays or to you")
                    l.listeners.toSortedMap().forEach { (address, st) -> ServiceRow(address, st) }
                }
                SettingsGroup("Discovery") {
                    if (!l.status.discoveryEnabled) SettingRow("Off", "Other devices find this phone only through the addresses they have for it")
                    l.discovery.toSortedMap().forEach { (method, st) -> ServiceRow(method, st, unusedHere(st)) }
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
    SettingRow(name, unused ?: st.error ?: "Working", trailing = { Icon(icon, null, Modifier.size(20.dp), tint = tint) })
}
