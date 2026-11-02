package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.DeviceWindows
import eu.stedb.mysyncthing.core.MINUTES_PER_DAY
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.Stretch
import eu.stedb.mysyncthing.core.SyncWindow
import eu.stedb.mysyncthing.core.WeekMask
import eu.stedb.mysyncthing.core.mask
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** The texts of per-device sync windows: the device screen's row, the editor's result card. */
class DeviceWindowsTextTest {
    private val server = Fixtures.server
    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun deviceRowSaysTheDefaultOrWhatTheWindowsGive() {
        assertEquals("Same as the global sync windows (default)", ownWindowsText(RunConditions(), server).en())
        // Off is the default too, whatever windows are kept for later.
        val off = RunConditions(deviceWindows = mapOf(server to DeviceWindows(false, listOf(SyncWindow.NIGHT))))
        assertEquals("Same as the global sync windows (default)", ownWindowsText(off, server).en())
        assertEquals("Every day 18:00–23:00 · syncs 14\u00a0h a week", ownWindowsText(Fixtures.ownWindows, server).en())
        assertEquals("Every day 21:00–23:00 · never syncs", ownWindowsText(Fixtures.ownWindows, Fixtures.tablet).en())
        val two = Fixtures.withServerWindows(SyncWindow(at(9), at(10), setOf(6)), SyncWindow(at(9), at(10), setOf(7)))
        assertEquals("Sat 09:00–10:00, and 1 more · syncs 2\u00a0h a week", ownWindowsText(two, server).en())
    }

    @Test
    fun effectiveWindowsGroupTheDays() {
        val m = Fixtures.shortOverlap.mask(server)
        assertEquals(listOf("Mon–Fri 19:30–20:00"), effectiveText(m).map { it.en() })
        // Across midnight and the week's end: one line.
        assertEquals(listOf("Every day 22:00–07:00 (next day)"), effectiveText(WeekMask.of(listOf(SyncWindow.NIGHT))).map { it.en() })
        // A stretch longer than a day is named from its start to its end.
        val day = WeekMask.of(listOf(SyncWindow(at(18), at(6), setOf(5)), SyncWindow(at(6), at(18), setOf(6))))
        assertEquals(listOf("Fri 18:00–Sat 18:00"), effectiveText(day).map { it.en() })
        // At most four lines.
        val five = WeekMask.of((1..5).map { SyncWindow(at(it), at(it) + 30, setOf(it)) })
        assertEquals(listOf("Mon 01:00–01:30", "Tue 02:00–02:30", "Wed 03:00–03:30", "and 2 more"), effectiveText(five).map { it.en() })
    }

    @Test
    fun stretchesForPeople() {
        assertEquals("Tue 06:30–07:00", stretchText(Stretch(MINUTES_PER_DAY + at(6, 30), 30)).en())
        assertEquals("Sun 22:00–Mon 07:00", stretchText(Stretch(6 * MINUTES_PER_DAY + at(22), 9 * 60)).en())
        assertEquals("Any time", stretchText(Stretch(0, 7 * MINUTES_PER_DAY)).en())
    }

    /** Property: the grouped windows cover exactly the minutes of the mask, when no stretch lasts a day. */
    @Test
    fun effectiveWindowsAreTheMask() {
        val r = Random(1102)
        var checked = 0
        repeat(3_000) {
            fun windows() = List(r.nextInt(1, 4)) {
                val start = r.nextInt(MINUTES_PER_DAY)
                SyncWindow(start, (start + r.nextInt(1, MINUTES_PER_DAY)) % MINUTES_PER_DAY, (1..7).filter { r.nextBoolean() }.toSet().ifEmpty { setOf(r.nextInt(1, 8)) })
            }
            val m = WeekMask.of(windows()) and WeekMask.of(windows())
            if (m.stretches().any { it.length >= MINUTES_PER_DAY }) return@repeat
            assertEquals(m, WeekMask.of(effectiveWindows(m)))
            checked++
        }
        check(checked > 1_000) { "too few cases: $checked" }
    }
}
