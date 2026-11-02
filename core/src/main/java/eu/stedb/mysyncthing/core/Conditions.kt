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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
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
 * Why device windows hold one device: outside its own sync windows ([next] when they next let it
 * sync), or [never], when they never overlap the global ones.
 */
data class Hold(val reason: UiText, val next: LocalDateTime? = null, val never: Boolean = false)

/** "Never syncs: its windows don't overlap the global ones". */
val NEVER_SYNCS = text(R.string.device_never_syncs)

/**
 * The devices their own sync windows hold now ([RunConditions.deviceWindows]), by ID. Judged on
 * what both windows allow together, so "next" is when the device really syncs next, and a device
 * whose windows never overlap the global ones is held for good. The global windows themselves
 * hold every device ([blockedBy]); a device is in here whenever the clock keeps it from syncing.
 */
fun RunConditions.deviceHolds(now: LocalDateTime = LocalDateTime.now()): Map<String, Hold> =
    deviceWindows.filterValues { it.on }.mapNotNull { (id, _) ->
        val m = mask(id)
        when {
            m.isEmpty -> id to Hold(NEVER_SYNCS, never = true)
            m.isOpen(now) -> null
            else -> id to Hold(nextText(R.string.device_outside_next, R.string.device_outside_next_day, m.nextOpen(now)!!, now), m.nextOpen(now))
        }
    }.toMap()

/** "… next 22:00", with the weekday when [next] is on another day than [now]. */
fun nextText(sameDay: Int, otherDay: Int, next: LocalDateTime, now: LocalDateTime): UiText {
    val time = timeText(next.hour * 60 + next.minute)
    return if (next.toLocalDate() == now.toLocalDate()) text(sameDay, time) else text(otherDay, shortDay(next.dayOfWeek), time)
}

/** Every window the clock must wake up for: the global ones while on, and each device's own while on. */
fun RunConditions.clockWindows(): List<SyncWindow> =
    (if (timeSchedule) windows else emptyList()) + deviceWindows.values.filter { it.on }.flatMap { it.windows }

/** Whether [blockedBy] gives the global sync windows' reason at [now]. */
fun RunConditions.blockedBySchedule(now: LocalDateTime): Boolean = !pausedByUser && timeSchedule && !windows.isOpen(now)

/**
 * What the devices need under the run conditions, from what the record holds ([ConditionHolds])
 * and the devices now. A device held is paused, also one added or resumed meanwhile; the user's
 * own pause of it is recorded once, when the hold starts. When the hold ends only the devices the
 * user had not paused are resumed. [hold] is written before anything is paused (unrecorded, a
 * pause would never be undone), [released] after the resumes, when [release] is set.
 */
internal data class ConditionPause(
    val hold: ConditionHolds? = null,
    val pauseAll: Boolean = false,
    val pause: List<String> = emptyList(),
    val resume: List<String> = emptyList(),
    val release: Boolean = false,
    val released: ConditionHolds? = null,
)

/**
 * Why the run conditions pause devices now: every device ([blockedBy], null while syncing is
 * allowed; [bySchedule] when that is the global sync windows) or single devices by their own
 * windows ([devices]), and the record of who paused what ([holds], see [conditionPause]). The
 * core's own `paused` then says only that a device is paused, not who wanted it.
 */
data class DevicePause(
    val blockedBy: UiText? = null,
    val holds: ConditionHolds? = null,
    val devices: Map<String, Hold> = emptyMap(),
    val bySchedule: Boolean = false,
) {
    /** The run conditions pause every device. */
    val allBlocked: Boolean get() = blockedBy != null

    /** The run conditions pause [d], for every device or by its own windows. */
    fun blocked(d: Device): Boolean = blockedBy != null || d.deviceID in devices

    /**
     * Why the run conditions pause [d], for its card and screen, or null. Never syncing comes
     * first, as no other change helps; then the reasons for every device, but the global windows
     * only when the device has none of its own: its own hold says when it really syncs next.
     */
    fun reason(d: Device): UiText? {
        val own = devices[d.deviceID]
        return when {
            own?.never == true -> own.reason
            blockedBy != null && !(bySchedule && own != null) -> blockedBy
            else -> own?.reason
        }
    }

    /** The user's own choice for [d]: while the run conditions hold it, the recorded one. */
    fun byUser(d: Device): Boolean = holds?.takeIf { it.holds(d.deviceID) }?.let { d.deviceID in it.userPaused } ?: d.paused

    /** Paused now, by the user or by the run conditions. */
    fun paused(d: Device): Boolean = d.paused || blocked(d)
}

/** The core's device pauses, as the steps below use them; a fake in tests. */
internal interface CorePauses {
    suspend fun devices(): List<Device>

    /** All devices when [id] is null. */
    suspend fun setPaused(id: String?, paused: Boolean)
}

internal fun RestClient.pauses(): CorePauses {
    val client = this
    return object : CorePauses {
        override suspend fun devices() = client.devices()
        override suspend fun setPaused(id: String?, paused: Boolean) {
            if (id == null) client.setAllPaused(paused) else client.setDevicePaused(id, paused)
        }
    }
}

/** Orders the service's run-condition steps and the user's own pauses, so neither undoes the other. */
private val pauseLock = Mutex()

/**
 * Pauses or resumes [id] as the user's own choice. While the run conditions hold it only the
 * record changes: the device stays paused until they let it go, and then follows it.
 */
suspend fun setDevicePausedByUser(client: RestClient, settings: Settings, id: String, paused: Boolean) =
    setDevicePausedByUser(client.pauses(), settings, id, paused)

internal suspend fun setDevicePausedByUser(core: CorePauses, settings: Settings, id: String, paused: Boolean) = pauseLock.withLock {
    when (settings.recordUserPause(id, paused)) {
        true -> Unit
        false -> core.setPaused(id, paused)
        null -> throw IOException("app settings not writable")
    }
}

/**
 * Brings the devices in line with the run conditions ([conditionPause]): [reason] holds every
 * device, [own] the devices their own windows hold. False if a step failed.
 */
internal suspend fun applyConditions(core: CorePauses, settings: Settings, reason: UiText?, own: Set<String> = emptySet()): Boolean = pauseLock.withLock {
    val plan = runCatching { conditionPause(reason != null, own, settings.conditionHolds(), core.devices()) }.getOrElse { return false }
    // Unrecorded, the pause would never be undone.
    if (plan.hold != null && !settings.setConditionHolds(plan.hold)) return false
    runCatching {
        if (plan.pauseAll) core.setPaused(null, true)
        plan.pause.forEach { core.setPaused(it, true) }
        plan.resume.forEach { core.setPaused(it, false) }
    }.isSuccess && (!plan.release || settings.setConditionHolds(plan.released))
}

/**
 * The steps for [devices] when [all] of them, or those in [own], are to be held, given what
 * [recorded] holds already. A device that leaves the hold (or the list) is let go: resumed unless
 * the user had paused it too. One the conditions pause but the core shows running (resumed in the
 * web GUI, or a step that failed) is paused again.
 */
internal fun conditionPause(all: Boolean, own: Set<String>, recorded: ConditionHolds?, devices: List<Device>): ConditionPause {
    val ids = devices.mapTo(LinkedHashSet()) { it.deviceID }
    val blocked = if (all) ids else ids.filterTo(LinkedHashSet()) { it in own }
    val held = recorded?.let { it.held ?: ids } ?: emptySet()
    val userPaused = recorded?.userPaused ?: emptySet()
    val newly = devices.filter { it.deviceID in blocked && it.deviceID !in held }
    val nowHeld = held + newly.map { it.deviceID }
    val nowUserPaused = userPaused + newly.filter { it.paused }.map { it.deviceID }
    // A record from before own windows is written out with its devices, so it no longer means "all".
    val hold = if (newly.isNotEmpty() || (recorded != null && recorded.held == null && nowHeld.isNotEmpty())) ConditionHolds(nowHeld, nowUserPaused) else null
    val running = devices.filter { it.deviceID in blocked && !it.paused }.map { it.deviceID }
    val letGo = held - blocked
    val resume = devices.filter { it.deviceID in letGo && it.paused && it.deviceID !in userPaused }.map { it.deviceID }
    val left = ConditionHolds(nowHeld - letGo, nowUserPaused - letGo).takeIf { it.held!!.isNotEmpty() }
    // Also a record that holds nothing (a start with no devices) is cleared.
    val release = letGo.isNotEmpty() || (recorded != null && nowHeld.isEmpty())
    return ConditionPause(
        hold = hold,
        pauseAll = all && running.isNotEmpty(),
        pause = if (all) emptyList() else running,
        resume = resume,
        release = release,
        released = left.takeIf { release },
    )
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
