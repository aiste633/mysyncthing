package eu.stedb.mysyncthing.core

import android.app.ApplicationExitInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MemoryLimitTest {
    private fun exit(at: Long, reason: Int = ApplicationExitInfo.REASON_OTHER, description: String? = "MemoryLimiter: rss over limit") =
        ProcessExit(reason, description, at, 300_000)

    // Only REASON_OTHER with "MemoryLimiter" counts: low memory, crashes and a user stop are other notices or none.
    @Test
    fun onlyTheMemoryLimiterCounts() {
        assertEquals(true, exit(1).byMemoryLimit())
        assertEquals(false, exit(1, ApplicationExitInfo.REASON_LOW_MEMORY).byMemoryLimit())
        assertEquals(false, exit(1, ApplicationExitInfo.REASON_CRASH_NATIVE).byMemoryLimit())
        assertEquals(false, exit(1, description = "user request after error").byMemoryLimit())
        assertEquals(false, exit(1, description = null).byMemoryLimit())
    }

    // The newest end after the last notice is reported; ends up to and including that time are not reported again.
    @Test
    fun newestUnseenEnd() {
        val exits = listOf(exit(100), exit(300), exit(200), exit(400, ApplicationExitInfo.REASON_USER_REQUESTED, "stop"))
        assertEquals(300L, exits.newestMemoryLimitEnd(seenUntil = 150)?.timestamp)
        assertNull(exits.newestMemoryLimitEnd(seenUntil = 300))
        assertNull(emptyList<ProcessExit>().newestMemoryLimitEnd(seenUntil = 0))
    }

    private val gib = 1L shl 30

    // Android 17's table (memory-limiter-config.xml), by MemTotal in MiB: the bounds of each tier.
    @Test
    fun serviceLimitTiers() {
        assertNull(serviceLimitMib(3199))
        assertEquals(1024L, serviceLimitMib(3200))
        assertEquals(1024L, serviceLimitMib(4799))
        assertEquals(2048L, serviceLimitMib(4800))
        assertEquals(3072L, serviceLimitMib(6800))
        assertEquals(4096L, serviceLimitMib(9216))
        assertEquals(5120L, serviceLimitMib(14336))
        assertEquals(5120L, serviceLimitMib(18431))
        assertNull(serviceLimitMib(18432))
    }

    // Android's limit less the margin, in MB; none before Android 17 or where Android sets none.
    @Test
    fun automaticLimit() {
        assertEquals(773L, CoreMemoryLimit.automaticMb(37, 4 * gib - 300 * (1L shl 20))) // a "4 GB" phone: 1,073 MB - 300
        assertEquals(1847L, CoreMemoryLimit.automaticMb(37, 6 * gib))
        assertEquals(3994L, CoreMemoryLimit.automaticMb(37, 12 * gib))
        assertEquals(5068L, CoreMemoryLimit.automaticMb(38, 16 * gib))
        assertEquals(0L, CoreMemoryLimit.automaticMb(36, 8 * gib))
        assertEquals(0L, CoreMemoryLimit.automaticMb(37, 2 * gib))
        assertEquals(0L, CoreMemoryLimit.automaticMb(37, 24 * gib))
        assertEquals(1073L, CoreMemoryLimit.androidLimitMb(37, 4 * gib - 300 * (1L shl 20)))
    }

    // The user's own limit wins over the automatic one, 0 (no limit) too.
    @Test
    fun effectiveLimit() {
        assertEquals(1847L, CoreMemoryLimit.effectiveMb(null, 37, 6 * gib))
        assertEquals(0L, CoreMemoryLimit.effectiveMb(0, 37, 6 * gib))
        assertEquals(500L, CoreMemoryLimit.effectiveMb(500, 36, 6 * gib))
    }

    @Test
    fun ownLimitAgreesWithTheBridge() {
        // The bridge's own test (TestValidationContract) checks it against the same file.
        val contract = Json.parseToJsonElement(File("../go/bridge/testdata/validation-contract.json").readText()).jsonObject
        val cases = contract.getValue("memoryLimit").jsonArray.map { it.jsonObject }
        assert(cases.isNotEmpty())
        cases.forEach { c ->
            val ok = CoreMemoryLimit.valid(c.getValue("mb").jsonPrimitive.long, c.getValue("ramBytes").jsonPrimitive.long)
            assertEquals(c.toString(), c.getValue("ok").jsonPrimitive.boolean, ok)
        }
    }

    // /proc/self/status: RssAnon and VmSwap in kB; a field missing reads as unknown.
    @Test
    fun anonSwapFromStatus() {
        val status = "Name:\tmysyncthing\nVmRSS:\t  310444 kB\nRssAnon:\t  180000 kB\nRssFile:\t  120000 kB\nVmSwap:\t    2048 kB\n"
        assertEquals((180000L + 2048) * 1024, anonSwapBytes(status))
        assertNull(anonSwapBytes("RssAnon:\t1 kB\n"))
        assertNull(anonSwapBytes(""))
    }

    // The core's freed memory goes back when the last screen leaves the front, not when one screen covers
    // another and not when a screen is only recreated (new theme, language or font size).
    @Test
    fun screensGoneOnlyWhenTheLastOneLeaves() {
        var gone = 0
        val screens = ScreensGone { gone++ }
        screens.started("main")
        screens.started("scanner")
        screens.stopped("main", recreating = false)
        assertEquals("covered by another screen", 0, gone)
        screens.stopped("scanner", recreating = false)
        assertEquals("the last screen left", 1, gone)

        screens.started("main")
        screens.stopped("main", recreating = true)
        assertEquals("recreated", 1, gone)
        screens.started("main again")
        screens.stopped("main again", recreating = false)
        assertEquals("left after the recreation", 2, gone)
    }

    // The activity starts the service, so its own start came before the service could see it.
    @Test
    fun aScreenStartedBeforeTheServiceCountsWhenItLeaves() {
        var gone = 0
        ScreensGone { gone++ }.stopped("main", recreating = false)
        assertEquals(1, gone)
    }
}
