package eu.stedb.mysyncthing.core

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SettingsMigrationTest {
    @Test
    fun newInstallStartsWithoutMobileData() {
        assertEquals(RunConditions(meteredWifi = true, mobileData = false), mutablePreferencesOf().runConditions())
    }

    @Test
    fun installsSetUpBeforeTheWifiChoiceMeantAnyNetwork() {
        val p = mutablePreferencesOf(Settings.SETUP_DONE to true)
        assertEquals(RunConditions(meteredWifi = true, mobileData = true), p.runConditions())
    }

    @Test
    fun oldKeysCarryOver() {
        fun old(wifiOnly: Boolean, unmeteredOnly: Boolean, chargingOnly: Boolean) = mutablePreferencesOf(
            Settings.SETUP_DONE to true, Settings.WIFI_ONLY to wifiOnly, Settings.UNMETERED_ONLY to unmeteredOnly,
            Settings.CHARGING_ONLY to chargingOnly,
        ).runConditions()
        assertEquals(RunConditions(meteredWifi = true, mobileData = true), old(false, false, false))
        assertEquals(RunConditions(meteredWifi = true, mobileData = false), old(true, false, false))
        assertEquals(RunConditions(meteredWifi = false, mobileData = false), old(true, true, false))
        // "Unmetered only" allowed unmetered mobile data, which now counts as mobile data: off.
        assertEquals(RunConditions(meteredWifi = false, mobileData = false), old(false, true, false))
        assertEquals(PowerSource.ChargingOnly, old(false, false, true).power)
    }

    @Test
    fun newKeysWinAndUnknownPowerIsAny() {
        val p = mutablePreferencesOf(
            Settings.WIFI_ONLY to true, Settings.MOBILE_DATA to true, Settings.CHARGING_ONLY to true, Settings.POWER to "Solar",
        )
        assertEquals(true, p.runConditions().mobileData)
        // An unknown stored name falls back to the old key, then to Any.
        assertEquals(PowerSource.ChargingOnly, p.runConditions().power)
    }

    /** Property: migrated conditions never allow a network or power state the old ones paused on. */
    @Test
    fun migrationNeverAllowsMore() {
        val r = Random(5)
        repeat(5_000) {
            val prefs = mutablePreferencesOf()
            fun maybe(key: Preferences.Key<Boolean>) { if (r.nextBoolean()) prefs[key] = r.nextBoolean() }
            maybe(Settings.SETUP_DONE); maybe(Settings.WIFI_ONLY); maybe(Settings.UNMETERED_ONLY); maybe(Settings.CHARGING_ONLY)
            val wifiOnly = prefs[Settings.WIFI_ONLY] ?: (prefs[Settings.SETUP_DONE] != true)
            val unmeteredOnly = prefs[Settings.UNMETERED_ONLY] ?: false
            val c = prefs.runConditions()
            // Old rules, for Wi-Fi/Ethernet and mobile data (unmetered or not), roaming or not.
            for (d in listOf(
                DeviceState(online = true, wifi = true, unmetered = true), DeviceState(online = true, wifi = true),
                DeviceState(online = true, mobile = true), DeviceState(online = true, mobile = true, unmetered = true),
                DeviceState(online = true, mobile = true, roaming = true),
            )) {
                val oldAllowed = (!wifiOnly || d.wifi) && (!unmeteredOnly || d.unmetered)
                if (!oldAllowed) assertTrue("$c on $d", c.copy(respectBatterySaver = false).blockedBy(d) != null)
            }
        }
    }
}
