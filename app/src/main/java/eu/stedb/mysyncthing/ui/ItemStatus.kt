package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.Completion
import eu.stedb.mysyncthing.core.Connection
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderStatus
import eu.stedb.mysyncthing.core.SyncState

/** State and short text for one folder or device card. */
data class ItemStatus(val state: SyncState, val text: String, val progress: Float? = null)

fun folderStatus(f: Folder, st: FolderStatus?): ItemStatus = when {
    f.paused -> ItemStatus(SyncState.Paused, "Paused")
    st == null -> ItemStatus(SyncState.Stopped, "Unknown")
    st.state == "error" -> ItemStatus(SyncState.Problem, "Error")
    st.errors > 0 -> ItemStatus(SyncState.Problem, "${st.errors} ${if (st.errors == 1) "error" else "errors"}")
    (st.needBytes > 0 && f.type != "sendonly") || st.state == "syncing" || st.state == "sync-preparing" -> {
        val p = if (st.globalBytes > 0) 1f - st.needBytes.toFloat() / st.globalBytes else null
        ItemStatus(SyncState.Syncing, p?.let { "Syncing ${(it * 100).toInt().coerceAtMost(99)} %" } ?: "Syncing", p)
    }
    f.needsReview(st) -> ItemStatus(SyncState.NeedsInput, if (f.type == "receiveonly") "Local changes" else "Out of sync")
    st.state == "scanning" || st.state == "scan-waiting" -> ItemStatus(SyncState.Syncing, "Scanning")
    f.devices.size <= 1 -> ItemStatus(SyncState.NeedsInput, "Not shared") // the list includes this device
    else -> ItemStatus(SyncState.UpToDate, "Up to date")
}

/** A device being offline is normal (a laptop asleep), so it is grey, not red. */
fun deviceStatus(d: Device, conn: Connection?, completion: Completion?): ItemStatus = when {
    d.paused -> ItemStatus(SyncState.Paused, "Paused")
    conn?.connected != true -> ItemStatus(SyncState.Stopped, "Disconnected")
    completion != null && completion.needBytes > 0 ->
        ItemStatus(SyncState.Syncing, "Syncing ${completion.completion.toInt()} %", (completion.completion / 100).toFloat())
    else -> ItemStatus(SyncState.UpToDate, "Up to date")
}

/**
 * "LAN", "Internet" or "Relay" (relayed connections are much slower), plus the address when
 * [withAddress]: on the device's own screen, not on its card.
 */
fun connectionText(conn: Connection?, withAddress: Boolean = true): String? {
    if (conn?.connected != true) return null
    val via = when {
        conn.type.startsWith("relay") -> "Relay"
        conn.isLocal -> "LAN"
        else -> "Internet"
    }
    return if (withAddress) "$via · ${conn.address}" else via
}
