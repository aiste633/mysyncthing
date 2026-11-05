package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.ConditionHolds
import eu.stedb.mysyncthing.core.DevicePause
import eu.stedb.mysyncthing.core.Hold
import eu.stedb.mysyncthing.core.PAUSED_BY_USER
import eu.stedb.mysyncthing.core.R
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Who paused a device, as its card and its screen say it. */
class DevicePauseTextTest {
    private val laptop = Device("A", "Laptop")
    private val mobileData = DevicePause(text(R.string.pause_reason_mobile_data), holds = ConditionHolds(null, setOf("B")))

    @Test fun notPausedSaysNothing() {
        assertNull(pauseReason(laptop, DevicePause()).en())
        assertNull(pauseTitle(laptop, DevicePause()).en())
    }

    @Test fun ownPause() {
        val paused = laptop.copy(paused = true)
        assertEquals("By you", pauseReason(paused, DevicePause()).en())
        assertEquals("Paused by you", pauseTitle(paused, DevicePause()).en())
    }

    @Test fun runConditionSaysWhy() {
        val paused = laptop.copy(paused = true)
        assertEquals("On mobile data", pauseReason(paused, mobileData).en())
        assertEquals("Paused: On mobile data", pauseTitle(paused, mobileData).en())
        assertEquals(SyncState.Paused, deviceStatus(laptop, null, null, mobileData).state) // before the service paused it
    }

    /** The run condition decides when it syncs, so it says that while it holds; "By you" after. */
    @Test fun blockWinsOverOwnPause() {
        val tablet = Device("B", "Tablet", paused = true)
        assertEquals("On mobile data", pauseReason(tablet, mobileData).en())
        assertEquals("Paused: On mobile data", pauseTitle(tablet, mobileData).en())
        assertEquals("By you", pauseReason(tablet, DevicePause()).en())
    }

    /** The same for its own windows: closed, they say when it syncs next; open, "By you". */
    @Test fun ownWindowWinsOverOwnPause() {
        val tablet = Device("B", "Tablet", paused = true)
        val closed = DevicePause(devices = mapOf("B" to Hold(text(R.string.device_outside_next, "22:00"))), holds = ConditionHolds(setOf("B"), setOf("B")))
        assertEquals("Outside its sync windows · next 22:00", pauseReason(tablet, closed).en())
        assertEquals("Paused: Outside its sync windows · next 22:00", pauseTitle(tablet, closed).en())
        assertEquals("By you", pauseReason(tablet, DevicePause()).en())
    }

    /** The user's pause of all syncing must not read like a device's own "By you". */
    @Test fun pauseOfEverything() {
        val all = DevicePause(PAUSED_BY_USER, holds = ConditionHolds(null, emptySet()))
        val paused = laptop.copy(paused = true)
        assertEquals("All paused by you", pauseReason(paused, all).en())
        assertEquals("All paused by you", pauseTitle(paused, all).en())
    }
}
