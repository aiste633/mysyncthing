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
import org.junit.Test

class StartDecisionTest {
    private class CountingStore : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        var writes = 0
        override val data = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writes++
            return transform(state.value).also { state.value = it }
        }
    }

    @Test
    fun aStopHoldsForTheBootItWasMadeIn() {
        assertTrue(stopHolds(stoppedAt = 7, now = 7))
        assertFalse(stopHolds(stoppedAt = 7, now = 8))
        assertFalse(stopHolds(stoppedAt = null, now = 7))
        assertTrue(stopHolds(stoppedAt = -1, now = -1)) // boot unknown: until the user starts again
        assertFalse(stopHolds(stoppedAt = -1, now = 3))
    }

    /**
     * A BOOT_COMPLETED that is no boot (Android 15+ sends one when the app
     * leaves the stopped state) changes nothing: only a new boot count ends the Stop.
     */
    @Test
    fun onlyARealBootEndsTheStop() = runBlocking {
        var boot = 12
        val store = CountingStore()
        val settings = Settings(store) { boot }
        assertTrue(settings.setStoppedByUser(true))
        assertTrue(settings.stoppedByUser.first())
        // Force-stop, install, first start: same boot, so the Stop holds whatever broadcast came.
        assertTrue(Settings(store) { boot }.stoppedByUser.first())
        boot++
        assertFalse(settings.stoppedByUser.first())
        // A Stop in the new boot is recorded with it.
        assertTrue(settings.setStoppedByUser(true))
        assertTrue(settings.stoppedByUser.first())
        assertTrue(settings.setStoppedByUser(false))
        assertFalse(settings.stoppedByUser.first())
    }

    /** Asked on every start, so an unchanged value writes nothing (flash wear). */
    @Test
    fun stoppedByUserWritesOnlyChanges() = runBlocking {
        val store = CountingStore()
        val settings = Settings(store) { 3 }
        repeat(5) { assertTrue(settings.setStoppedByUser(false)) }
        assertEquals(0, store.writes)
        repeat(3) { assertTrue(settings.setStoppedByUser(true)) }
        assertEquals(1, store.writes)
        repeat(3) { assertTrue(settings.setStoppedByUser(false)) }
        assertEquals(2, store.writes)
    }
}
