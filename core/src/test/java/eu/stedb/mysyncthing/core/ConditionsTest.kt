package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConditionsTest {
    private val wifiCharging = DeviceState(wifi = true, unmetered = true, charging = true)

    @Test
    fun defaultsAllowSyncUnlessBatterySaver() {
        assertNull(RunConditions().blockedBy(DeviceState()))
        assertEquals("Battery saver on", RunConditions().blockedBy(DeviceState(batterySaver = true)))
    }

    @Test
    fun eachConditionBlocks() {
        assertEquals("Not on Wi-Fi", RunConditions(wifiOnly = true).blockedBy(DeviceState()))
        assertEquals("Network is metered", RunConditions(unmeteredOnly = true).blockedBy(DeviceState(wifi = true)))
        assertEquals("Not charging", RunConditions(chargingOnly = true).blockedBy(DeviceState(wifi = true)))
        assertNull(RunConditions(wifiOnly = true, unmeteredOnly = true, chargingOnly = true).blockedBy(wifiCharging))
    }

    @Test
    fun userPauseWinsOverEverything() {
        assertEquals(PAUSED_BY_USER, RunConditions(pausedByUser = true).blockedBy(wifiCharging))
    }
}
