package eu.stedb.mysyncthing.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

private val Context.dataStore by preferencesDataStore("settings")

/**
 * Secrets shared between the app and the core. New at every core start and kept only in
 * memory, so a login that got out ends with the next start. Only the app knows the GUI
 * login; the bridge refuses any change to it.
 */
data class Credentials(val apiKey: String, val guiUser: String, val guiPassword: String) {
    internal companion object {
        /** 64 characters; bcrypt, which hashes the GUI password, reads at most 72 bytes. */
        const val TOKEN_CHARS = 64

        fun random() = Credentials(randomToken(), randomToken(), randomToken())

        /** [TOKEN_CHARS] characters of A-Z, a-z, 0-9, - and _ from 48 random bytes, as the bridge wants them (go/bridge token). */
        fun randomToken(): String {
            val b = ByteArray(TOKEN_CHARS * 3 / 4).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
    }
}

/** Which power source the core may sync on. */
@Serializable
enum class PowerSource { Any, ChargingOnly, BatteryOnly }

/**
 * When the core is allowed to sync. Unmetered Wi-Fi and Ethernet are always
 * allowed; the network switches add the others (see [blockedBy]). Serialized
 * with the bridge's names (go/bridge runConditions) for import and export.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RunConditions(
    /** Metered Wi-Fi and Ethernet, hotspots and tethering. */
    val meteredWifi: Boolean = true,
    val mobileData: Boolean = false,
    /** Mobile data abroad; only with [mobileData]. */
    val roaming: Boolean = false,
    val power: PowerSource = PowerSource.Any,
    val respectBatterySaver: Boolean = true,
    val startOnBoot: Boolean = true,
    /**
     * Set by the widget or Settings; enforced like the other conditions. Never
     * in a backup: an import keeps this phone's own (go/bridge unpause).
     */
    @Transient val pausedByUser: Boolean = false,
    /** Sync only in [windows]. */
    val timeSchedule: Boolean = false,
    /** Kept while [timeSchedule] is off, so turning it on again restores them. */
    val windows: List<SyncWindow> = emptyList(),
    /**
     * Devices' own sync windows, by device ID. They narrow the global ones: a device with its
     * own syncs only while both are open (see [mask]). Left out of an export without any, so
     * app versions from before them still read it.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val deviceWindows: Map<String, DeviceWindows> = emptyMap(),
)

/** A device's own sync windows: like [RunConditions.timeSchedule] and its windows, kept while off. */
@Serializable
data class DeviceWindows(val on: Boolean = false, val windows: List<SyncWindow> = emptyList())

/** [id]'s own windows set to [w]; an entry that says nothing (off, no windows) is dropped. */
fun RunConditions.withDeviceWindows(id: String, w: DeviceWindows): RunConditions =
    copy(deviceWindows = if (w == DeviceWindows()) deviceWindows - id else deviceWindows + (id to w))

/**
 * The devices the run conditions pause now ([held]), and which of them the user had paused too
 * ([userPaused]): their own choice, kept for when the hold ends. [held] is null for a record from
 * before devices had their own windows, when a hold always took every device.
 */
data class ConditionHolds(val held: Set<String>?, val userPaused: Set<String>) {
    fun holds(id: String): Boolean = held?.contains(id) ?: true
}

/** How the core connects to other devices, relays and discovery servers. */
@Serializable
enum class ProxyType(val scheme: String) { None(""), Socks5("socks5"), Http("http") }

/**
 * A proxy for the core (go/bridge SetProxy). With [only], nothing goes around
 * it: no direct fallback, no QUIC, no NAT port mapping. The local network is
 * always reached directly.
 */
@Serializable
data class ProxySettings(
    val type: ProxyType = ProxyType.None,
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val password: String = "",
    val only: Boolean = false,
) {
    override fun toString() = "ProxySettings($type, $host, $port, user=${user.isNotEmpty()}, password=${password.isNotEmpty()}, only=$only)"

    companion object {
        /** Orbot and other Tor apps listen here. */
        val TOR = ProxySettings(ProxyType.Socks5, "127.0.0.1", 9050, only = true)
    }
}

/**
 * The app's own settings. Writes fail when the phone's storage is full; they
 * return false then (the stored values stay as they were) instead of throwing,
 * as a throw from a launched coroutine would crash the app.
 */
class Settings internal constructor(
    private val store: DataStore<Preferences>,
    /** Which boot of the phone this is; -1 when unknown. */
    private val bootCount: () -> Int = { -1 },
) {
    constructor(context: Context) : this(context.applicationContext.dataStore, {
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    })

    /** Every write goes through here. */
    private suspend fun write(block: (MutablePreferences) -> Unit): Boolean = try {
        store.edit(block)
        true
    } catch (e: IOException) {
        false
    }

    /**
     * Removes the login that versions up to 0.2.0 kept here; the login is now new at every start
     * ([Credentials.random]). Writes only when one is there; false when it couldn't be removed,
     * which doesn't matter for the start: the core no longer accepts it.
     */
    suspend fun forgetStoredLogin(): Boolean {
        val legacy = listOf(API_KEY, GUI_USER, GUI_PASSWORD)
        val prefs = store.data.first()
        if (legacy.none { it in prefs }) return true
        return write { p -> legacy.forEach { p.remove(it) } }
    }

    val runConditions: Flow<RunConditions> = store.data.map { it.runConditions() }

    suspend fun update(transform: (RunConditions) -> RunConditions): Boolean = write {
        val c = transform(it.runConditions())
        it[METERED_WIFI] = c.meteredWifi
        it[MOBILE_DATA] = c.mobileData
        it[ROAMING] = c.roaming
        it[POWER] = c.power.name
        // Read only when the keys above are missing (see runConditions).
        it.remove(WIFI_ONLY)
        it.remove(UNMETERED_ONLY)
        it.remove(CHARGING_ONLY)
        it[RESPECT_BATTERY_SAVER] = c.respectBatterySaver
        it[START_ON_BOOT] = c.startOnBoot
        it[PAUSED_BY_USER] = c.pausedByUser
        it[TIME_SCHEDULE] = c.timeSchedule
        it[WINDOWS] = json.encodeToString(c.windows)
        if (c.deviceWindows.isEmpty()) it.remove(DEVICE_WINDOWS) else it[DEVICE_WINDOWS] = json.encodeToString(c.deviceWindows)
    }

    /** True once the user tapped Done on the setup checklist. */
    val setupDone: Flow<Boolean> = store.data.map { it[SETUP_DONE] ?: false }

    /** Also stores the mobile data choice, whose default depends on [SETUP_DONE] (see [runConditions]). */
    suspend fun setSetupDone(): Boolean = write {
        it[MOBILE_DATA] = it.runConditions().mobileData
        it[SETUP_DONE] = true
    }

    /**
     * The user stopped Syncthing (Settings or the notification): the app then
     * doesn't start it when it opens, until the user starts it or the phone
     * restarts. Stored as the boot it happened in, so it ends by itself with the
     * next boot. A broadcast can't tell: Android 15+ also sends BOOT_COMPLETED
     * when the app leaves the stopped state (a force-stop, an install).
     */
    val stoppedByUser: Flow<Boolean> = store.data.map { sameBoot(it[STOPPED_AT_BOOT], bootCount()) }

    /** Writes only a change: it is asked on every start. */
    suspend fun setStoppedByUser(stopped: Boolean): Boolean = when {
        stoppedByUser.first() == stopped -> true
        stopped -> write { it[STOPPED_AT_BOOT] = bootCount() }
        else -> write { it.remove(STOPPED_AT_BOOT) }
    }

    /**
     * Syncthing was started in this boot. An update of the app kills it without
     * a stop, so the update starts it again, with Start at boot off too, unless
     * the user stopped it since ([stoppedByUser]).
     */
    val startedThisBoot: Flow<Boolean> = store.data.map { sameBoot(it[STARTED_AT_BOOT], bootCount()) }

    /** Writes only once a boot: it is asked on every start. */
    suspend fun setStartedThisBoot(): Boolean = startedThisBoot.first() || write { it[STARTED_AT_BOOT] = bootCount() }

    /** Until when Android's records of process ends were read for the memory notice; null before the first read. */
    suspend fun memoryExitsSeenUntil(): Long? = store.data.first()[MEMORY_EXITS_SEEN]

    suspend fun setMemoryExitsSeenUntil(at: Long): Boolean = write { it[MEMORY_EXITS_SEEN] = at }

    /** True after a phone migration brought the identity along, until the user has seen the note. */
    val movedFromOtherPhone: Flow<Boolean> = store.data.map { it[MOVED] ?: false }

    suspend fun setMovedFromOtherPhone(moved: Boolean): Boolean = write { it[MOVED] = moved }

    /** Set by an import: the next start finds no database, and the import screen has explained why. */
    suspend fun setFreshDatabaseExpected(expected: Boolean): Boolean = write { it[FRESH_DATABASE] = expected }

    /** Whether [setFreshDatabaseExpected] was set, clearing it; false when it can't be cleared. */
    suspend fun takeFreshDatabaseExpected(): Boolean {
        var expected = false
        return write {
            expected = it[FRESH_DATABASE] ?: false
            it.remove(FRESH_DATABASE)
        } && expected
    }

    /**
     * The proxy the core starts with. An unreadable stored value becomes one
     * the bridge refuses, so the core fails to start with a "proxy" error
     * instead of connecting directly.
     */
    val proxy: Flow<ProxySettings> = store.data.map { p ->
        val stored = p[PROXY] ?: return@map ProxySettings()
        runCatching { json.decodeFromString<ProxySettings>(stored) }.getOrDefault(ProxySettings(ProxyType.Socks5, only = true))
    }

    suspend fun setProxy(p: ProxySettings): Boolean = write { it[PROXY] = json.encodeToString(p) }

    /**
     * The devices the run conditions hold paused, with the user's own choice for each, so the end
     * of a hold does not undo it. Null when they hold none.
     */
    suspend fun conditionHolds(): ConditionHolds? = store.data.first().conditionHolds()

    val holds: Flow<ConditionHolds?> = store.data.map { it.conditionHolds() }.distinctUntilChanged()

    /** The user's own pauses among the held devices (see [conditionHolds]); null when none is held. */
    suspend fun conditionPause(): Set<String>? = conditionHolds()?.userPaused

    suspend fun setConditionHolds(h: ConditionHolds?): Boolean = write {
        if (h == null || h.held?.isEmpty() == true) {
            it.remove(CONDITION_PAUSE)
            it.remove(CONDITION_HELD)
        } else {
            it[CONDITION_PAUSE] = h.userPaused
            if (h.held == null) it.remove(CONDITION_HELD) else it[CONDITION_HELD] = h.held
        }
    }

    /**
     * While the run conditions hold [device], records the user's own choice for it, for when the
     * hold ends. False when they don't hold it, null when the write failed. Callers hold the lock
     * that orders the record's writers.
     */
    suspend fun recordUserPause(device: String, paused: Boolean): Boolean? {
        val h = conditionHolds()?.takeIf { it.holds(device) } ?: return false
        val userPaused = if (paused) h.userPaused + device else h.userPaused - device
        return if (setConditionHolds(h.copy(userPaused = userPaused))) true else null
    }

    private fun Preferences.conditionHolds(): ConditionHolds? {
        val userPaused = this[CONDITION_PAUSE] ?: return null
        return ConditionHolds(this[CONDITION_HELD], userPaused)
    }

    internal companion object {
        // The login of versions up to 0.2.0; only removed now (forgetStoredLogin).
        val API_KEY = stringPreferencesKey("api_key")
        val GUI_USER = stringPreferencesKey("gui_user")
        val GUI_PASSWORD = stringPreferencesKey("gui_password")
        val METERED_WIFI = booleanPreferencesKey("metered_wifi")
        val MOBILE_DATA = booleanPreferencesKey("mobile_data")
        val ROAMING = booleanPreferencesKey("roaming")
        val POWER = stringPreferencesKey("power")

        // Before metered Wi-Fi, mobile data and power became separate choices.
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val UNMETERED_ONLY = booleanPreferencesKey("unmetered_only")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val RESPECT_BATTERY_SAVER = booleanPreferencesKey("respect_battery_saver")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val PAUSED_BY_USER = booleanPreferencesKey("paused_by_user")
        /** The user's own pauses among the held devices; present while any device is held. */
        val CONDITION_PAUSE = stringSetPreferencesKey("condition_pause")
        /** The held devices; missing in a record from before own windows: then every device. */
        val CONDITION_HELD = stringSetPreferencesKey("condition_held")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val MOVED = booleanPreferencesKey("moved_from_other_phone")
        val FRESH_DATABASE = booleanPreferencesKey("fresh_database_expected")
        val STOPPED_AT_BOOT = intPreferencesKey("stopped_by_user_at_boot")
        val STARTED_AT_BOOT = intPreferencesKey("started_at_boot")
        val MEMORY_EXITS_SEEN = longPreferencesKey("memory_exits_seen_until")
        val PROXY = stringPreferencesKey("proxy")
        val TIME_SCHEDULE = booleanPreferencesKey("time_schedule")
        val WINDOWS = stringPreferencesKey("sync_windows")
        val DEVICE_WINDOWS = stringPreferencesKey("device_sync_windows")

        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Something recorded in boot [at] (a Stop, a start) holds in boot [now]; with the boot unknown (-1), until it is recorded again. */
internal fun sameBoot(at: Int?, now: Int): Boolean = at != null && at == now

/**
 * Stored run conditions. Values from before the separate network and power
 * choices are read only while the new keys are missing, and never allow more
 * than they did: "unmetered only" also allowed unmetered mobile data, which
 * now counts as mobile data and stays off.
 */
internal fun Preferences.runConditions(): RunConditions {
    // Mobile data can cost money, so new installs start without it (setup asks).
    // Installs set up before that had no stored value and meant "any network".
    val wifiOnly = this[Settings.WIFI_ONLY] ?: (this[Settings.SETUP_DONE] != true)
    val unmeteredOnly = this[Settings.UNMETERED_ONLY] ?: false
    return RunConditions(
        meteredWifi = this[Settings.METERED_WIFI] ?: !unmeteredOnly,
        mobileData = this[Settings.MOBILE_DATA] ?: (!wifiOnly && !unmeteredOnly),
        roaming = this[Settings.ROAMING] ?: false,
        power = this[Settings.POWER]?.let { v -> PowerSource.entries.find { it.name == v } }
            ?: if (this[Settings.CHARGING_ONLY] == true) PowerSource.ChargingOnly else PowerSource.Any,
        respectBatterySaver = this[Settings.RESPECT_BATTERY_SAVER] ?: true,
        startOnBoot = this[Settings.START_ON_BOOT] ?: true,
        pausedByUser = this[Settings.PAUSED_BY_USER] ?: false,
        timeSchedule = this[Settings.TIME_SCHEDULE] ?: false,
        windows = storedWindows(),
        deviceWindows = storedDeviceWindows(),
    )
}

/**
 * The stored time windows. One that can't be read or is not valid any more
 * is left out; with none left, a time schedule that is on lets nothing
 * through (see [blockedBy]), as the user wanted syncing restricted.
 */
private fun Preferences.storedWindows(): List<SyncWindow> {
    val stored = this[Settings.WINDOWS] ?: return emptyList()
    return runCatching { Settings.json.decodeFromString<List<SyncWindow>>(stored) }.getOrDefault(emptyList())
        .filter { syncWindowError(it) == null }.take(MAX_SYNC_WINDOWS)
}

/**
 * The stored own windows of devices. As for [storedWindows], a window that can't be read or is
 * not valid is left out, and so is a device whose entry can't be read: it then follows the global
 * windows, as a device the user never set any for. One that is on with no windows left lets
 * nothing through.
 */
private fun Preferences.storedDeviceWindows(): Map<String, DeviceWindows> {
    val stored = this[Settings.DEVICE_WINDOWS] ?: return emptyMap()
    val all = runCatching { Settings.json.decodeFromString<Map<String, DeviceWindows>>(stored) }.getOrDefault(emptyMap())
    return all.mapValues { (_, w) -> w.copy(windows = w.windows.filter { syncWindowError(it) == null }.take(MAX_SYNC_WINDOWS)) }
}
