package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.DevicePause
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
    private val mobileData = DevicePause(text(R.string.pause_reason_mobile_data), recorded = setOf("B"))

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

    /** Its own pause outlasts the run condition, so that is what it says. */
    @Test fun ownPauseWinsWhileBlocked() {
        val tablet = Device("B", "Tablet", paused = true)
        assertEquals("By you", pauseReason(tablet, mobileData).en())
        assertEquals("Paused by you", pauseTitle(tablet, mobileData).en())
    }

    /** The user's pause of all syncing must not read like a device's own "By you". */
    @Test fun pauseOfEverything() {
        val all = DevicePause(PAUSED_BY_USER, recorded = emptySet())
        val paused = laptop.copy(paused = true)
        assertEquals("All paused by you", pauseReason(paused, all).en())
        assertEquals("All paused by you", pauseTitle(paused, all).en())
    }
}
