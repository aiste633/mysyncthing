package eu.stedb.mysyncthing.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

private val Context.dataStore by preferencesDataStore("settings")

/**
 * Secrets shared between the app and the core. Generated once, kept in app-private storage.
 * Only the app knows the GUI login; the bridge refuses any change to it.
 */
data class Credentials(val apiKey: String, val guiUser: String, val guiPassword: String)

/** Which power source the core may sync on. */
@Serializable
enum class PowerSource { Any, ChargingOnly, BatteryOnly }

/**
 * When the core is allowed to sync. Unmetered Wi-Fi and Ethernet are always
 * allowed; the network switches add the others (see [blockedBy]). Serialized
 * with the bridge's names (go/bridge runConditions) for import and export.
 */
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
    /** Set by the widget or Settings; enforced like the other conditions. */
    val pausedByUser: Boolean = false,
)

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

    /** Every write but [credentials]'s goes through here. */
    private suspend fun write(block: (MutablePreferences) -> Unit): Boolean = try {
        store.edit(block)
        true
    } catch (e: IOException) {
        false
    }

    /**
     * Throws [IOException] when new credentials can't be stored: the core must
     * not start with a login the next start would not know.
     */
    suspend fun credentials(): Credentials {
        val prefs = store.data.first()
        prefs.credentials()?.takeIf { it.guiPassword.length >= TOKEN_CHARS }?.let { return it }
        // Also replaces the short GUI login of earlier versions; the bridge applies it at start.
        val created = Credentials(prefs[API_KEY] ?: randomToken(), randomToken(), randomToken())
        store.edit {
            it[API_KEY] = created.apiKey
            it[GUI_USER] = created.guiUser
            it[GUI_PASSWORD] = created.guiPassword
        }
        return created
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
    val stoppedByUser: Flow<Boolean> = store.data.map { stopHolds(it[STOPPED_AT_BOOT], bootCount()) }

    /** Writes only a change: it is asked on every start. */
    suspend fun setStoppedByUser(stopped: Boolean): Boolean = when {
        stoppedByUser.first() == stopped -> true
        stopped -> write { it[STOPPED_AT_BOOT] = bootCount() }
        else -> write { it.remove(STOPPED_AT_BOOT) }
    }

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
     * Devices the user had paused before a run condition paused everything,
     * so resuming does not undo the user's own choice. Null when not paused by us.
     */
    suspend fun conditionPause(): Set<String>? = store.data.first()[CONDITION_PAUSE]

    suspend fun setConditionPause(userPaused: Set<String>?): Boolean =
        write { if (userPaused == null) it.remove(CONDITION_PAUSE) else it[CONDITION_PAUSE] = userPaused }

    private fun Preferences.credentials(): Credentials? {
        val key = this[API_KEY] ?: return null
        val user = this[GUI_USER] ?: return null
        val password = this[GUI_PASSWORD] ?: return null
        return Credentials(key, user, password)
    }

    internal companion object {
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
        val CONDITION_PAUSE = stringSetPreferencesKey("condition_pause")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val MOVED = booleanPreferencesKey("moved_from_other_phone")
        val FRESH_DATABASE = booleanPreferencesKey("fresh_database_expected")
        val STOPPED_AT_BOOT = intPreferencesKey("stopped_by_user_at_boot")
        val PROXY = stringPreferencesKey("proxy")

        val json = Json { ignoreUnknownKeys = true }

        /** 64 characters; bcrypt, which hashes the GUI password, reads at most 72 bytes. */
        const val TOKEN_CHARS = 64

        fun randomToken(): String {
            val b = ByteArray(TOKEN_CHARS * 3 / 4).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
    }
}

/** A Stop made in boot [stoppedAt] holds in boot [now]; with the boot unknown (-1), until the user starts again. */
internal fun stopHolds(stoppedAt: Int?, now: Int): Boolean = stoppedAt != null && stoppedAt == now

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
    )
}
