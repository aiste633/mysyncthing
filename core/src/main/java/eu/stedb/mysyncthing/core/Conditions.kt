package eu.stedb.mysyncthing.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDateTime

/** Device state relevant to run conditions. */
data class DeviceState(
    val online: Boolean = false,
    val wifi: Boolean = false,
    /** Mobile data, also behind a VPN; false on Wi-Fi even when the phone shares mobile data too. */
    val mobile: Boolean = false,
    val roaming: Boolean = false,
    val unmetered: Boolean = false,
    val charging: Boolean = false,
    val batterySaver: Boolean = false,
)

const val PAUSED_BY_USER = "Paused by you"

/**
 * Null when syncing is allowed, otherwise a short reason for the notification/UI.
 * Unmetered Wi-Fi and Ethernet are always allowed. Mobile data follows its own
 * switches whether or not Android calls it metered; any other network (metered
 * Wi-Fi, a hotspot, Bluetooth or USB tethering) follows [RunConditions.meteredWifi].
 * A time schedule comes right after the user's own pause: it is the user's
 * plan, and its reason says when syncing resumes.
 */
fun RunConditions.blockedBy(d: DeviceState, now: LocalDateTime = LocalDateTime.now()): String? = when {
    pausedByUser -> PAUSED_BY_USER
    timeSchedule && !windows.isOpen(now) -> outsideText(windows, now)
    // Offline with a network limit stays paused, so the next network is checked before the core uses it.
    !d.online && !(mobileData && meteredWifi) -> "No network"
    d.online && d.mobile && !mobileData -> "On mobile data"
    d.online && d.mobile && d.roaming && !roaming -> "Roaming"
    d.online && !d.mobile && !d.unmetered && !meteredWifi -> if (d.wifi) "Wi-Fi is metered" else "Network is metered"
    power == PowerSource.ChargingOnly && !d.charging -> "Not charging"
    power == PowerSource.BatteryOnly && d.charging -> "Charging"
    respectBatterySaver && d.batterySaver -> "Battery saver on"
    else -> null
}

fun deviceState(context: Context): Flow<DeviceState> = combine(network(context), power(context)) { n, p ->
    n.copy(charging = p.charging, batterySaver = p.batterySaver)
}.distinctUntilChanged()

private fun network(context: Context): Flow<DeviceState> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            trySend(networkState(caps::hasTransport, caps::hasCapability))
        }

        override fun onLost(network: Network) {
            trySend(DeviceState())
        }
    }
    trySend(DeviceState())
    cm.registerDefaultNetworkCallback(cb)
    awaitClose { cm.unregisterNetworkCallback(cb) }
}

/** The default network's state from its transports and capabilities (NetworkCapabilities constants). */
internal fun networkState(hasTransport: (Int) -> Boolean, hasCapability: (Int) -> Boolean): DeviceState {
    val wifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    val mobile = !wifi && hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    return DeviceState(
        online = true,
        wifi = wifi,
        mobile = mobile,
        roaming = mobile && !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
        unmetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}

private fun power(context: Context): Flow<DeviceState> = callbackFlow {
    val pm = context.getSystemService(PowerManager::class.java)
    fun read(battery: Intent?): DeviceState {
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return DeviceState(charging = plugged != 0, batterySaver = pm.isPowerSaveMode)
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            trySend(read(c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))))
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
        addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
    }
    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    trySend(read(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))))
    awaitClose { context.unregisterReceiver(receiver) }
}
