package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MulticastLockTest {
    private val paused = text(R.string.pause_reason_user)

    // Every combination: the lock is wanted only on Wi-Fi, with local discovery on in a loaded snapshot, and not paused.
    @Test
    fun wantedOnlyWhenItCanHelp() {
        val flags = listOf(false, true)
        for (wifi in flags) for (loaded in flags) for (local in flags) for (blocked in flags) {
            val want = wantsMulticast(
                DeviceState(online = true, wifi = wifi),
                Snapshot(loaded = loaded, localDiscovery = local),
                if (blocked) paused else null,
            )
            assertEquals("wifi=$wifi loaded=$loaded local=$local blocked=$blocked", wifi && loaded && local && !blocked, want)
        }
    }

    // Before the first refresh, and after the core stops, the snapshot is empty: no lock.
    @Test
    fun emptySnapshotWantsNoLock() {
        assertEquals(false, wantsMulticast(DeviceState(online = true, wifi = true), Snapshot(), null))
    }
}
