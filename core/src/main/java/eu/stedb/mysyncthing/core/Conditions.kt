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

val PAUSED_BY_USER = text(R.string.pause_reason_user)

/**
 * Null when syncing is allowed, otherwise a short reason for the notification/UI.
 * Unmetered Wi-Fi and Ethernet are always allowed. Mobile data follows its own
 * switches whether or not Android calls it metered; any other network (metered
 * Wi-Fi, a hotspot, Bluetooth or USB tethering) follows [RunConditions.meteredWifi].
 * A time schedule comes right after the user's own pause: it is the user's
 * plan, and its reason says when syncing resumes.
 */
fun RunConditions.blockedBy(d: DeviceState, now: LocalDateTime = LocalDateTime.now()): UiText? = when {
    pausedByUser -> PAUSED_BY_USER
    timeSchedule && !windows.isOpen(now) -> outsideText(windows, now)
    // Offline with a network limit stays paused, so the next network is checked before the core uses it.
    !d.online && !(mobileData && meteredWifi) -> text(R.string.pause_reason_no_network)
    d.online && d.mobile && !mobileData -> text(R.string.pause_reason_mobile_data)
    d.online && d.mobile && d.roaming && !roaming -> text(R.string.pause_reason_roaming)
    d.online && !d.mobile && !d.unmetered && !meteredWifi ->
        text(if (d.wifi) R.string.pause_reason_metered_wifi else R.string.pause_reason_metered_network)
    power == PowerSource.ChargingOnly && !d.charging -> text(R.string.pause_reason_not_charging)
    power == PowerSource.BatteryOnly && d.charging -> text(R.string.pause_reason_charging)
    respectBatterySaver && d.batterySaver -> text(R.string.pause_reason_battery_saver)
    else -> null
}

/**
 * What the devices need under the run conditions, from the devices the user had paused already
 * ([recorded], null while syncing is allowed) and the devices now. While syncing is blocked every
 * device is paused, also one added or resumed meanwhile, and the user's own pauses are recorded
 * once; when syncing is allowed again only the other devices are resumed.
 */
internal data class ConditionPause(
    val record: Set<String>? = null,
    val pauseAll: Boolean = false,
    val resume: List<String> = emptyList(),
    val clearRecord: Boolean = false,
)

internal fun conditionPause(reason: UiText?, recorded: Set<String>?, devices: List<Device>): ConditionPause = when {
    reason != null -> ConditionPause(
        record = if (recorded == null) devices.filter { it.paused }.mapTo(mutableSetOf()) { it.deviceID } else null,
        pauseAll = devices.any { !it.paused },
    )
    recorded != null -> ConditionPause(resume = devices.filter { it.paused && it.deviceID !in recorded }.map { it.deviceID }, clearRecord = true)
    else -> ConditionPause()
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
