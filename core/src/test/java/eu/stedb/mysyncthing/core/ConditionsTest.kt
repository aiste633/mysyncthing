package eu.stedb.mysyncthing.core

import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConditionsTest {
    private val homeWifi = DeviceState(online = true, wifi = true, unmetered = true)
    private val meteredWifi = DeviceState(online = true, wifi = true)
    private val mobile = DeviceState(online = true, mobile = true)
    private val roaming = mobile.copy(roaming = true)
    private val tether = DeviceState(online = true) // Bluetooth or USB, metered

    @Test
    fun defaultsAllowUnmeteredAndMeteredWifiOnly() {
        val c = RunConditions()
        assertNull(c.blockedBy(homeWifi))
        assertNull(c.blockedBy(meteredWifi))
        assertNull(c.blockedBy(tether))
        assertEquals("On mobile data", c.blockedBy(mobile))
        assertEquals("No network", c.blockedBy(DeviceState()))
        assertEquals("Battery saver on", c.blockedBy(homeWifi.copy(batterySaver = true)))
    }

    @Test
    fun eachConditionBlocks() {
        assertEquals("Wi-Fi is metered", RunConditions(meteredWifi = false).blockedBy(meteredWifi))
        assertEquals("Network is metered", RunConditions(meteredWifi = false).blockedBy(tether))
        assertEquals("Roaming", RunConditions(mobileData = true).blockedBy(roaming))
        assertNull(RunConditions(mobileData = true).blockedBy(mobile))
        assertNull(RunConditions(mobileData = true, roaming = true).blockedBy(roaming))
        // Roaming alone allows nothing: it only widens mobile data.
        assertEquals("On mobile data", RunConditions(roaming = true).blockedBy(roaming))
        assertEquals("Not charging", RunConditions(power = PowerSource.ChargingOnly).blockedBy(homeWifi))
        assertNull(RunConditions(power = PowerSource.ChargingOnly).blockedBy(homeWifi.copy(charging = true)))
        assertEquals("Charging", RunConditions(power = PowerSource.BatteryOnly).blockedBy(homeWifi.copy(charging = true)))
        assertNull(RunConditions(power = PowerSource.BatteryOnly).blockedBy(homeWifi))
    }

    @Test
    fun mobileDataIsMobileEvenWhenUnmetered() {
        assertEquals("On mobile data", RunConditions().blockedBy(mobile.copy(unmetered = true)))
    }

    @Test
    fun offlineStaysPausedUnderANetworkLimit() {
        assertEquals("No network", RunConditions(mobileData = false).blockedBy(DeviceState()))
        assertEquals("No network", RunConditions(meteredWifi = false, mobileData = true).blockedBy(DeviceState()))
        assertNull(RunConditions(meteredWifi = true, mobileData = true).blockedBy(DeviceState()))
    }

    @Test
    fun userPauseWinsOverEverything() {
        assertEquals(PAUSED_BY_USER, RunConditions(pausedByUser = true).blockedBy(homeWifi.copy(charging = true)))
    }

    /**
     * Property: syncing runs exactly when the network is one the conditions
     * allow and the power state fits; unmetered Wi-Fi and Ethernet always count.
     */
    @Test
    fun blocksExactlyWhatIsNotAllowed() {
        val r = Random(4)
        repeat(20_000) {
            val c = RunConditions(
                r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), PowerSource.entries.random(r),
                r.nextBoolean(), r.nextBoolean(), pausedByUser = false,
            )
            val online = r.nextInt(5) != 0
            val isMobile = online && r.nextBoolean()
            val d = DeviceState(
                online = online, wifi = online && !isMobile && r.nextBoolean(), mobile = isMobile,
                roaming = isMobile && r.nextBoolean(), unmetered = online && r.nextBoolean(),
                charging = r.nextBoolean(), batterySaver = r.nextBoolean(),
            )
            val networkOk = when {
                !d.online -> c.mobileData && c.meteredWifi
                d.mobile -> c.mobileData && (!d.roaming || c.roaming)
                else -> d.unmetered || c.meteredWifi
            }
            val powerOk = when (c.power) {
                PowerSource.Any -> true
                PowerSource.ChargingOnly -> d.charging
                PowerSource.BatteryOnly -> !d.charging
            }
            val ok = networkOk && powerOk && !(c.respectBatterySaver && d.batterySaver)
            assertEquals("$c on $d", ok, c.blockedBy(d) == null)
        }
    }

    @Test
    fun networkStateFromCapabilities() {
        fun state(vararg transports: Int, metered: Boolean = true, roaming: Boolean = false) =
            networkState({ it in transports }, { cap -> (cap == NET_CAPABILITY_NOT_METERED && !metered) || (cap == NET_CAPABILITY_NOT_ROAMING && !roaming) })
        assertEquals(DeviceState(online = true, wifi = true, unmetered = true), state(TRANSPORT_WIFI, metered = false))
        assertEquals(DeviceState(online = true, wifi = true), state(TRANSPORT_ETHERNET))
        assertEquals(DeviceState(online = true, mobile = true), state(TRANSPORT_CELLULAR))
        assertEquals(DeviceState(online = true, mobile = true, roaming = true), state(TRANSPORT_CELLULAR, roaming = true))
        // A VPN carries its underlying network's transport; Wi-Fi wins when Android reports both.
        assertEquals(DeviceState(online = true, mobile = true, roaming = true), state(TRANSPORT_VPN, TRANSPORT_CELLULAR, roaming = true))
        assertEquals(DeviceState(online = true, wifi = true, unmetered = true), state(TRANSPORT_WIFI, TRANSPORT_CELLULAR, metered = false, roaming = true))
        val bt = state(TRANSPORT_BLUETOOTH)
        assertTrue(bt.online && !bt.wifi && !bt.mobile && !bt.unmetered)
    }
}
