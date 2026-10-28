package eu.stedb.mysyncthing.core

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Syncthing's local discovery uses IPv4 broadcast and IPv6 multicast (localAnnounceMCAddr),
 * and Android's Wi-Fi stack drops multicast for apps that hold no multicast lock: on
 * an IPv6-only network, devices on the same Wi-Fi would never find each other. The
 * lock keeps the Wi-Fi chip passing every multicast packet up, which costs battery,
 * so it is wanted only when it can help: on Wi-Fi (Ethernet counts too; the lock
 * does nothing there), with the core's local discovery on, and syncing not paused.
 */
internal fun wantsMulticast(device: DeviceState, snapshot: Snapshot, blockedBy: UiText?): Boolean =
    device.wifi && snapshot.loaded && snapshot.localDiscovery && blockedBy == null

/** Holds the Wi-Fi multicast lock while [wanted] is true; releases it when it turns false or on cancellation. */
internal suspend fun holdMulticastLock(context: Context, wanted: Flow<Boolean>) {
    val lock = context.getSystemService(WifiManager::class.java)
        .createMulticastLock("mysyncthing:local-discovery")
        .apply { setReferenceCounted(false) }
    try {
        wanted.distinctUntilChanged().collect { if (it) lock.acquire() else if (lock.isHeld) lock.release() }
    } finally {
        if (lock.isHeld) lock.release()
    }
}
