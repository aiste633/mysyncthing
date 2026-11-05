package eu.stedb.mysyncthing.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A store on a phone whose storage can fill up: then every write fails as DataStore's does (ENOSPC on the temp file). */
internal class FillableStore : DataStore<Preferences> {
    val state = MutableStateFlow(emptyPreferences())
    var full = false
    override val data = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        if (full) throw IOException("Unable to rename settings.preferences_pb.tmp: write failed: ENOSPC (No space left on device)")
        state.value = next
        return next
    }
}

class SettingsWriteTest {
    @Test
    fun writesOnAFullStorageFailWithoutThrowing() = runBlocking {
        val store = FillableStore()
        val settings = Settings(store)
        assertTrue(settings.update { it.copy(power = PowerSource.ChargingOnly) })
        store.full = true
        assertFalse(settings.update { it.copy(respectBatterySaver = false) })
        assertFalse(settings.setSetupDone())
        assertFalse(settings.setMovedFromOtherPhone(true))
        assertFalse(settings.setConditionHolds(ConditionHolds(setOf("A"), emptySet())))
        assertFalse(settings.setProxy(ProxySettings.TOR))
        // Nothing changed: what was stored before the storage filled up stays.
        val c = settings.runConditions.first()
        assertEquals(PowerSource.ChargingOnly, c.power)
        assertEquals(ProxySettings(), settings.proxy.first())
        assertTrue(c.respectBatterySaver)
        assertFalse(settings.setupDone.first())
        store.full = false
        assertTrue(settings.update { it.copy(respectBatterySaver = false) })
        assertFalse(settings.runConditions.first().respectBatterySaver)
    }

    /** The login versions up to 0.2.0 stored is removed, with a write only when it is there; a full storage can't stop a start. */
    @Test
    fun storedLoginIsForgotten() = runBlocking {
        val store = FillableStore()
        val settings = Settings(store)
        store.full = true
        assertTrue(settings.forgetStoredLogin()) // nothing stored: no write
        store.full = false
        store.state.value = preferencesOf(
            Settings.API_KEY to "old-key", Settings.GUI_USER to "old-user", Settings.GUI_PASSWORD to "old-password",
            Settings.START_ON_BOOT to false,
        )
        store.full = true
        assertFalse(settings.forgetStoredLogin()) // fails without throwing
        store.full = false
        assertTrue(settings.forgetStoredLogin())
        val left = store.state.value
        assertTrue(listOf(Settings.API_KEY, Settings.GUI_USER, Settings.GUI_PASSWORD).none { it in left })
        assertEquals(false, left[Settings.START_ON_BOOT]) // the rest stays
    }

    /** A new login for every start, in the form the bridge takes (go/bridge token), and never the same twice. */
    @Test
    fun loginIsNewEachTime() {
        val token = Regex("[A-Za-z0-9_-]{64}")
        val logins = List(100) { Credentials.random() }
        val all = logins.flatMap { listOf(it.apiKey, it.guiUser, it.guiPassword) }
        assertTrue(all.all { token.matches(it) })
        assertEquals(all.size, all.toSet().size)
    }
}
