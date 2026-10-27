package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.R as CoreR
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.plural
import eu.stedb.mysyncthing.core.text
import eu.stedb.mysyncthing.core.Completion
import eu.stedb.mysyncthing.core.Connection
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderStatus
import eu.stedb.mysyncthing.core.SyncState

/** State and short text for one folder or device card. */
data class ItemStatus(val state: SyncState, val text: UiText, val progress: Float? = null)

fun folderStatus(f: Folder, st: FolderStatus?): ItemStatus = when {
    f.paused -> ItemStatus(SyncState.Paused, text(CoreR.string.status_paused))
    st == null -> ItemStatus(SyncState.Stopped, text(R.string.item_unknown))
    st.state == "error" -> ItemStatus(SyncState.Problem, text(R.string.item_error))
    st.errors > 0 -> ItemStatus(SyncState.Problem, plural(R.plurals.item_errors, st.errors))
    (st.needBytes > 0 && f.type != "sendonly") || st.state == "syncing" || st.state == "sync-preparing" -> {
        val p = if (st.globalBytes > 0) 1f - st.needBytes.toFloat() / st.globalBytes else null
        ItemStatus(SyncState.Syncing, p?.let { text(CoreR.string.status_syncing_percent, (it * 100).toInt().coerceAtMost(99)) } ?: text(CoreR.string.status_syncing), p)
    }
    f.needsReview(st) -> ItemStatus(SyncState.NeedsInput, text(if (f.type == "receiveonly") R.string.item_local_changes else R.string.item_out_of_sync))
    st.state == "scanning" || st.state == "scan-waiting" -> ItemStatus(SyncState.Syncing, text(R.string.item_scanning))
    f.devices.size <= 1 -> ItemStatus(SyncState.NeedsInput, text(R.string.item_not_shared)) // the list includes this device
    else -> ItemStatus(SyncState.UpToDate, text(CoreR.string.status_up_to_date))
}

/** A device being offline is normal (a laptop asleep), so it is grey, not red. */
fun deviceStatus(d: Device, conn: Connection?, completion: Completion?): ItemStatus = when {
    d.paused -> ItemStatus(SyncState.Paused, text(CoreR.string.status_paused))
    conn?.connected != true -> ItemStatus(SyncState.Stopped, text(R.string.item_disconnected))
    completion != null && completion.needBytes > 0 ->
        ItemStatus(SyncState.Syncing, text(CoreR.string.status_syncing_percent, completion.completion.toInt()), (completion.completion / 100).toFloat())
    else -> ItemStatus(SyncState.UpToDate, text(CoreR.string.status_up_to_date))
}

/**
 * "LAN", "Internet" or "Relay" (relayed connections are much slower), plus the address when
 * [withAddress]: on the device's own screen, not on its card.
 */
fun connectionText(conn: Connection?, withAddress: Boolean = true): UiText? {
    if (conn?.connected != true) return null
    val via = text(
        when {
            conn.type.startsWith("relay") -> R.string.connection_relay
            conn.isLocal -> R.string.connection_lan
            else -> R.string.connection_internet
        },
    )
    return if (withAddress) text(R.string.connection_with_address, via, conn.address) else via
}
