package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.SyncWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import kotlin.random.Random

class ScheduleTextTest {
    @Test
    fun windowsForPeople() {
        assertEquals("Mon–Fri 22:00–07:00 (next day)", windowText(SyncWindow(22 * 60, 7 * 60, (1..5).toSet())).en())
        assertEquals("Sat, Sun 00:00–23:59", windowText(SyncWindow(0, 1439, setOf(6, 7))).en())
        assertEquals("Every day 09:05–17:00", windowText(SyncWindow(9 * 60 + 5, 17 * 60, (1..7).toSet())).en())
        assertEquals("Mon, Wed–Fri, Sun", daysText(setOf(1, 3, 4, 5, 7)).en())
        assertEquals("Any time", scheduleText(RunConditions(windows = listOf(SyncWindow(0, 60, setOf(1))))).en())
        assertEquals(
            "Tue 00:00–01:00, and 1 more",
            scheduleText(RunConditions(timeSchedule = true, windows = listOf(SyncWindow(0, 60, setOf(2)), SyncWindow(0, 60, setOf(3))))).en(),
        )
    }

    /** Property: the day text names exactly the picked days, in order, Monday first. */
    @Test
    fun dayTextNamesThePickedDays() {
        val names = DayOfWeek.entries.associateBy { it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) }
        val r = Random(30)
        repeat(2_000) {
            val days = (1..7).shuffled(r).take(r.nextInt(1, 7)).toSet()
            val named = daysText(days).en().split(", ").flatMap { part ->
                val ends = part.split("–").map { names.getValue(it).value }
                ends.first()..ends.last()
            }
            assertEquals(days.sorted(), named)
        }
    }
}
