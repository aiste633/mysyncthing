package eu.stedb.mysyncthing.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ForkPreviewTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** The bridge's output, pinned by its own test (TestPreviewJSONContract). */
    @Test
    fun decodesTheBridgeContract() {
        val p = json.decodeFromString<ForkPreview>(File("../go/bridge/testdata/fork/preview.json").readText())
        assertEquals("fork", p.source)
        assertEquals("SELF-ID", p.deviceID)
        assertEquals("Old phone", p.deviceName)
        assertEquals(37, p.configVersion)
        assertEquals(ForkFolder("photo_backup_01", "Photos", "/storage/emulated/0/DCIM/Camera", "sendonly", exists = true, marker = false), p.folders.single())
        assertEquals(ForkDevice("PEER-ID", "Example peer"), p.devices.single())
        assertEquals(
            RunConditions(meteredWifi = false, mobileData = true, power = PowerSource.BatteryOnly, respectBatterySaver = true, startOnBoot = false),
            p.conditions,
        )
        assertEquals(ProxySettings.TOR, p.proxy)
        assertEquals(listOf(ForkNotImported("networks"), ForkNotImported("folder_conditions", "Photos")), p.notImported)
    }

    /**
     * The export's settings file has the bridge's field names and every field,
     * as its strict reader wants (go/bridge TestOwnSettingsContract reads the same file).
     */
    @Test
    fun exportSettingsContract() {
        val written = ConfigExport.settingsJson(
            RunConditions(meteredWifi = true, mobileData = true, power = PowerSource.BatteryOnly, startOnBoot = true),
            ProxySettings(ProxyType.Socks5, "proxy.example.org", 1080, "me", "p@1:w/ord", only = true),
        )
        val golden = File("../go/bridge/testdata/own-settings.json").readText()
        assertEquals(Json.parseToJsonElement(golden), Json.parseToJsonElement(written))
        assertEquals(setOf("version", "conditions", "proxy"), Json.parseToJsonElement(written).jsonObject.keys)
    }
}
