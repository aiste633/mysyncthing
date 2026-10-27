package eu.stedb.mysyncthing.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** A store on a phone whose storage can fill up: then every write fails as DataStore's does (ENOSPC on the temp file). */
private class FillableStore : DataStore<Preferences> {
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
        assertFalse(settings.setConditionPause(setOf("A")))
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

    /** New credentials that can't be stored must fail the start, not run the core with a login the next start won't know. */
    @Test
    fun credentialsThatCantBeStoredThrow() = runBlocking {
        val store = FillableStore().apply { full = true }
        try {
            Settings(store).credentials()
            fail("credentials() returned without storing them")
        } catch (_: IOException) {
        }
        store.full = false
        val first = Settings(store).credentials()
        store.full = true
        assertEquals(first, Settings(store).credentials()) // stored ones need no write
    }
}
