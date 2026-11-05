package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Completion
import eu.stedb.mysyncthing.core.Connection
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.DevicePause
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderStatus
import eu.stedb.mysyncthing.core.PAUSED_BY_USER
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.plural
import eu.stedb.mysyncthing.core.isOpen
import eu.stedb.mysyncthing.core.text
import eu.stedb.mysyncthing.core.whenText
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import eu.stedb.mysyncthing.core.R as CoreR

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
fun deviceStatus(d: Device, conn: Connection?, completion: Completion?, pause: DevicePause = DevicePause()): ItemStatus = when {
    pause.paused(d) -> ItemStatus(SyncState.Paused, text(CoreR.string.status_paused))
    conn?.connected != true -> ItemStatus(SyncState.Stopped, text(R.string.item_disconnected))
    completion != null && completion.needBytes > 0 ->
        ItemStatus(SyncState.Syncing, text(CoreR.string.status_syncing_percent, completion.completion.toInt()), (completion.completion / 100).toFloat())
    else -> ItemStatus(SyncState.UpToDate, text(CoreR.string.status_up_to_date))
}

/**
 * Why [d] is paused, for its card: a run condition ("On mobile data"), its own windows or the
 * user's pause of all syncing while one of those holds it, as that is what decides when it syncs;
 * the user's own pause of it only once nothing else does. Null when it is not paused.
 */
fun pauseReason(d: Device, pause: DevicePause): UiText? = when {
    !pause.paused(d) -> null
    !pause.blocked(d) && pause.byUser(d) -> text(R.string.device_paused_by_you)
    pause.reason(d) == PAUSED_BY_USER -> text(R.string.device_all_paused_by_you)
    else -> pause.reason(d)
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

/** "Last seen 13:41", or "Never connected" for a device the core has not seen. */
fun lastSeenText(at: Instant?, now: ZonedDateTime = ZonedDateTime.now()): UiText =
    at?.let { text(R.string.device_last_seen, whenText(it, now)) } ?: text(R.string.device_never_connected)

/**
 * Whether the sync times are what pauses the devices now. The user's own pause comes first,
 * as in the run conditions; the run conditions must have paused the devices already.
 */
fun scheduleBlocks(rc: RunConditions, pause: DevicePause, now: LocalDateTime = LocalDateTime.now()): Boolean =
    pause.allBlocked && !rc.pausedByUser && rc.timeSchedule && !rc.windows.isOpen(now)
