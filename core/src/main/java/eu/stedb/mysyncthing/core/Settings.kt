package eu.stedb.mysyncthing.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.Base64

private val Context.dataStore by preferencesDataStore("settings")

/** Secrets shared between the app and the core. Generated once, kept in app-private storage. */
data class Credentials(val apiKey: String, val guiUser: String, val guiPassword: String)

/** When the core is allowed to sync. All false means "always". */
data class RunConditions(
    val wifiOnly: Boolean = false,
    val unmeteredOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val respectBatterySaver: Boolean = true,
    val startOnBoot: Boolean = true,
)

class Settings(context: Context) {
    private val store = context.applicationContext.dataStore

    suspend fun credentials(): Credentials {
        val prefs = store.data.first()
        prefs.credentials()?.let { return it }
        val created = Credentials(randomToken(32), "android", randomToken(24))
        store.edit {
            it[API_KEY] = created.apiKey
            it[GUI_USER] = created.guiUser
            it[GUI_PASSWORD] = created.guiPassword
        }
        return created
    }

    val runConditions: Flow<RunConditions> = store.data.map { it.runConditions() }

    suspend fun update(transform: (RunConditions) -> RunConditions) {
        store.edit {
            val c = transform(it.runConditions())
            it[WIFI_ONLY] = c.wifiOnly
            it[UNMETERED_ONLY] = c.unmeteredOnly
            it[CHARGING_ONLY] = c.chargingOnly
            it[RESPECT_BATTERY_SAVER] = c.respectBatterySaver
            it[START_ON_BOOT] = c.startOnBoot
        }
    }

    /**
     * Devices the user had paused before a run condition paused everything,
     * so resuming does not undo the user's own choice. Null when not paused by us.
     */
    suspend fun conditionPause(): Set<String>? = store.data.first()[CONDITION_PAUSE]

    suspend fun setConditionPause(userPaused: Set<String>?) {
        store.edit { if (userPaused == null) it.remove(CONDITION_PAUSE) else it[CONDITION_PAUSE] = userPaused }
    }

    private fun Preferences.runConditions() = RunConditions(
        wifiOnly = this[WIFI_ONLY] ?: false,
        unmeteredOnly = this[UNMETERED_ONLY] ?: false,
        chargingOnly = this[CHARGING_ONLY] ?: false,
        respectBatterySaver = this[RESPECT_BATTERY_SAVER] ?: true,
        startOnBoot = this[START_ON_BOOT] ?: true,
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
        val CONDITION_PAUSE = stringSetPreferencesKey("condition_pause")

        fun randomToken(bytes: Int): String {
            val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
    }
}
