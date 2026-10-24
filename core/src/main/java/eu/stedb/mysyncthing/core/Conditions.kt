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

/** Device state relevant to run conditions. */
data class DeviceState(
    val wifi: Boolean = false,
    val unmetered: Boolean = false,
    val charging: Boolean = false,
    val batterySaver: Boolean = false,
)

/** Null when syncing is allowed, otherwise a short reason for the notification/UI. */
fun RunConditions.blockedBy(d: DeviceState): String? = when {
    wifiOnly && !d.wifi -> "Not on Wi-Fi"
    unmeteredOnly && !d.unmetered -> "Network is metered"
    chargingOnly && !d.charging -> "Not charging"
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
            trySend(
                DeviceState(
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                )
            )
        }

        override fun onLost(network: Network) {
            trySend(DeviceState())
        }
    }
    trySend(DeviceState())
    cm.registerDefaultNetworkCallback(cb)
    awaitClose { cm.unregisterNetworkCallback(cb) }
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
