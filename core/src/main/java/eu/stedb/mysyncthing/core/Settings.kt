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
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

private val Context.dataStore by preferencesDataStore("settings")

/**
 * Secrets shared between the app and the core. Generated once, kept in app-private storage.
 * Only the app knows the GUI login; the bridge refuses any change to it.
 */
data class Credentials(val apiKey: String, val guiUser: String, val guiPassword: String)

/** When the core is allowed to sync. All false means "always". */
data class RunConditions(
    val wifiOnly: Boolean = false,
    val unmeteredOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val respectBatterySaver: Boolean = true,
    val startOnBoot: Boolean = true,
    /** Set by the widget or Settings; enforced like the other conditions. */
    val pausedByUser: Boolean = false,
)

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
        it[WIFI_ONLY] = c.wifiOnly
        it[UNMETERED_ONLY] = c.unmeteredOnly
        it[CHARGING_ONLY] = c.chargingOnly
        it[RESPECT_BATTERY_SAVER] = c.respectBatterySaver
        it[START_ON_BOOT] = c.startOnBoot
        it[PAUSED_BY_USER] = c.pausedByUser
    }

    /** True once the user tapped Done on the setup checklist. */
    val setupDone: Flow<Boolean> = store.data.map { it[SETUP_DONE] ?: false }

    /** Also stores the Wi-Fi choice, whose default depends on [SETUP_DONE] (see [runConditions]). */
    suspend fun setSetupDone(): Boolean = write {
        it[WIFI_ONLY] = it.runConditions().wifiOnly
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

    /**
     * Devices the user had paused before a run condition paused everything,
     * so resuming does not undo the user's own choice. Null when not paused by us.
     */
    suspend fun conditionPause(): Set<String>? = store.data.first()[CONDITION_PAUSE]

    suspend fun setConditionPause(userPaused: Set<String>?): Boolean =
        write { if (userPaused == null) it.remove(CONDITION_PAUSE) else it[CONDITION_PAUSE] = userPaused }

    private fun Preferences.runConditions() = RunConditions(
        // Mobile data can cost money, so new installs start on Wi-Fi only (setup asks).
        // Installs set up before that had no stored value and meant "any network".
        wifiOnly = this[WIFI_ONLY] ?: (this[SETUP_DONE] != true),
        unmeteredOnly = this[UNMETERED_ONLY] ?: false,
        chargingOnly = this[CHARGING_ONLY] ?: false,
        respectBatterySaver = this[RESPECT_BATTERY_SAVER] ?: true,
        startOnBoot = this[START_ON_BOOT] ?: true,
        pausedByUser = this[PAUSED_BY_USER] ?: false,
    )

    private fun Preferences.credentials(): Credentials? {
        val key = this[API_KEY] ?: return null
        val user = this[GUI_USER] ?: return null
        val password = this[GUI_PASSWORD] ?: return null
        return Credentials(key, user, password)
    }

    private companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val GUI_USER = stringPreferencesKey("gui_user")
        val GUI_PASSWORD = stringPreferencesKey("gui_password")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val UNMETERED_ONLY = booleanPreferencesKey("unmetered_only")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val RESPECT_BATTERY_SAVER = booleanPreferencesKey("respect_battery_saver")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val PAUSED_BY_USER = booleanPreferencesKey("paused_by_user")
        val CONDITION_PAUSE = stringSetPreferencesKey("condition_pause")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val MOVED = booleanPreferencesKey("moved_from_other_phone")
        val STOPPED_AT_BOOT = intPreferencesKey("stopped_by_user_at_boot")

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
