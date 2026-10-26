package eu.stedb.mysyncthing.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

class ForkPreviewTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** The bridge's output, pinned by its own test (TestPreviewJSONContract). */
    @Test
    fun decodesTheBridgeContract() {
        val p = json.decodeFromString<ForkPreview>(File("../go/bridge/testdata/fork/preview.json").readText())
        assertEquals("SELF-ID", p.deviceID)
        assertEquals("Old phone", p.deviceName)
        assertEquals(37, p.configVersion)
        assertEquals(ForkFolder("photo_backup_01", "Photos", "/storage/emulated/0/DCIM/Camera", "sendonly", exists = true, marker = false), p.folders.single())
        assertEquals(ForkDevice("PEER-ID", "Example peer"), p.devices.single())
        assertEquals(ForkConditions(unmeteredOnly = true, respectBatterySaver = true, startOnBoot = false), p.conditions)
        assertEquals(listOf(ForkNotImported("networks"), ForkNotImported("folder_conditions", "Photos")), p.notImported)
    }

    /** Applying imported conditions sets every field and keeps nothing of the old ones. */
    @Test
    fun applyToReplacesEveryCondition() {
        val r = Random(7)
        repeat(1000) {
            val c = ForkConditions(r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean())
            val old = RunConditions(r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean())
            assertEquals(
                RunConditions(c.wifiOnly, c.unmeteredOnly, c.chargingOnly, c.respectBatterySaver, c.startOnBoot, c.pausedByUser),
                c.applyTo(old),
            )
        }
    }
}
