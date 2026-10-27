package eu.stedb.mysyncthing.core

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random

class ScheduleTest {
    private val weekdays = (1..5).toSet()
    private val night = SyncWindow(22 * 60, 7 * 60, weekdays)

    /** Monday 2026-10-26, so day d of that week is 2026-10-(25 + d). */
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 25 + day, hour, minute)

    @Test
    fun aNightWindowRunsIntoTheNextDay() {
        assertTrue(night.isOpen(at(1, 22)))
        assertTrue(night.isOpen(at(2, 6, 59)))
        assertFalse(night.isOpen(at(2, 7)))
        assertFalse(night.isOpen(at(1, 21, 59)))
        // Friday night runs into Saturday; Saturday night does not start.
        assertTrue(night.isOpen(at(6, 3)))
        assertFalse(night.isOpen(at(6, 23)))
        // Monday morning belongs to Sunday's night, which is not picked.
        assertFalse(night.isOpen(at(1, 3)))
    }

    @Test
    fun aDayWindowEndsBeforeItsEnd() {
        val w = SyncWindow(9 * 60, 17 * 60, setOf(3))
        assertTrue(w.isOpen(at(3, 9)))
        assertTrue(w.isOpen(at(3, 16, 59)))
        assertFalse(w.isOpen(at(3, 17)))
        assertFalse(w.isOpen(at(4, 10)))
    }

    @Test
    fun theReasonSaysWhenSyncingResumes() {
        assertEquals("Outside sync times · next 22:00", outsideText(listOf(night), at(1, 13, 5)).en())
        assertEquals("Outside sync times · next Mon 22:00", outsideText(listOf(night), at(6, 12)).en())
        assertEquals("Outside sync times · next 09:05", outsideText(listOf(SyncWindow(9 * 60 + 5, 10 * 60, setOf(2))), at(2, 8)).en())
        assertEquals("Outside sync times", outsideText(emptyList(), at(2, 8)).en())
    }

    @Test
    fun aScheduleBlocksOnlyOutsideItsWindows() {
        val c = RunConditions(timeSchedule = true, windows = listOf(night))
        val online = DeviceState(online = true, wifi = true, unmetered = true)
        assertNull(c.blockedBy(online, at(1, 23)))
        assertEquals("Outside sync times · next 22:00", c.blockedBy(online, at(1, 12)).en())
        assertNull(c.copy(timeSchedule = false).blockedBy(online, at(1, 12)))
        assertEquals(PAUSED_BY_USER, c.copy(pausedByUser = true).blockedBy(online, at(1, 23)))
        // Turned on without a window, nothing goes through.
        assertEquals("Outside sync times", RunConditions(timeSchedule = true).blockedBy(online, at(1, 23)).en())
    }

    @Test
    fun fieldChecksAgreeWithTheBridge() {
        // The bridge's own test (TestValidationContract) checks it against the same file.
        val contract = Json.parseToJsonElement(File("../go/bridge/testdata/validation-contract.json").readText()).jsonObject
        contract.getValue("syncWindow").jsonArray.map { it.jsonObject }.forEach { c ->
            val days = c.getValue("days").jsonArray.map { it.jsonPrimitive.int }
            val w = SyncWindow(c.getValue("start").jsonPrimitive.int, c.getValue("end").jsonPrimitive.int, days.toSet())
            // A day twice is one day in a set (the bridge refuses the list); the form can't make it.
            if (w.days.size != days.size) return@forEach
            assertEquals(c.toString(), c.getValue("ok").jsonPrimitive.boolean, syncWindowError(w) == null)
        }
    }

    @Test
    fun scheduleChecks() {
        assertEquals("Add a time window", scheduleError(true, emptyList()).en())
        assertNull(scheduleError(false, emptyList()))
        assertEquals("At most 8 time windows", scheduleError(false, List(9) { night }).en())
        assertEquals("Pick at least one day", scheduleError(true, listOf(night, night.copy(days = emptySet()))).en())
        assertNull(scheduleError(true, List(8) { night }))
    }

    @Test
    fun storedWindowsThatAreNotValidAreLeftOut() {
        val p = mutablePreferencesOf(
            Settings.TIME_SCHEDULE to true,
            Settings.WINDOWS to """[{"start":60,"end":60,"days":[1]},{"start":1320,"end":420,"days":[1,2,3,4,5]}]""",
        )
        assertEquals(listOf(night), p.runConditions().windows)
        p[Settings.WINDOWS] = "not json"
        // Still on, with nothing let through.
        assertTrue(p.runConditions().timeSchedule)
        assertEquals(emptyList<SyncWindow>(), p.runConditions().windows)
    }

    @Test
    fun theScheduleIsStored() = runBlocking {
        val settings = Settings(FillableStore())
        assertTrue(settings.update { it.copy(timeSchedule = true, windows = listOf(night, SyncWindow(0, 1439, setOf(6, 7)))) })
        val c = settings.runConditions.first()
        assertTrue(c.timeSchedule)
        assertEquals(listOf(night, SyncWindow(0, 1439, setOf(6, 7))), c.windows)
        assertTrue(settings.update { it.copy(timeSchedule = false) })
        assertEquals(2, settings.runConditions.first().windows.size)
    }

    // --- Properties, checked against a separate reading of the rules: minutes of the week.

    private val minutesPerWeek = 7 * MINUTES_PER_DAY

    private fun minuteOfWeek(t: LocalDateTime) = (t.dayOfWeek.value - 1) * MINUTES_PER_DAY + t.hour * 60 + t.minute

    /** Open when some window instance [start, end) on the circle of the week holds t. */
    private fun reference(ws: List<SyncWindow>, t: LocalDateTime): Boolean = ws.any { w ->
        val length = (w.end - w.start + MINUTES_PER_DAY) % MINUTES_PER_DAY
        w.days.any { d ->
            val from = (d - 1) * MINUTES_PER_DAY + w.start
            (minuteOfWeek(t) - from + minutesPerWeek) % minutesPerWeek < length
        }
    }

    private fun Random.window() = SyncWindow(
        nextInt(MINUTES_PER_DAY),
        nextInt(MINUTES_PER_DAY),
        (1..7).shuffled(this).take(nextInt(1, 8)).toSet(),
    ).let { if (it.start == it.end) it.copy(end = (it.end + 1) % MINUTES_PER_DAY) else it }

    private fun Random.windows() = List(nextInt(1, MAX_SYNC_WINDOWS + 1)) { window() }

    private fun Random.time(): LocalDateTime =
        LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(nextLong(0, 366L * MINUTES_PER_DAY)).plusSeconds(nextLong(60))

    @Test
    fun isOpenMatchesTheWeekCircle() {
        val r = Random(27)
        repeat(20_000) {
            val ws = r.windows()
            val t = r.time()
            assertEquals("$ws at $t", reference(ws, t), ws.isOpen(t))
        }
    }

    @Test
    fun nothingChangesBeforeTheNextChange() {
        val r = Random(28)
        repeat(300) {
            val ws = r.windows()
            val t = r.time()
            val next = ws.nextChange(t)!!
            assertTrue(next > t && next <= t.plusDays(8))
            var m = t.truncatedTo(ChronoUnit.MINUTES)
            while (m < next) {
                assertEquals("$ws from $t to $next, at $m", ws.isOpen(t), ws.isOpen(m))
                m = m.plusMinutes(1)
            }
        }
    }

    @Test
    fun theNextOpeningIsTheFirstOpenMinute() {
        val r = Random(29)
        repeat(300) {
            val ws = r.windows()
            val t = r.time()
            if (ws.isOpen(t)) return@repeat
            val next = ws.nextOpen(t)!!
            assertTrue("$ws at $next", ws.isOpen(next))
            var m = t.truncatedTo(ChronoUnit.MINUTES)
            while (m < next) {
                assertFalse("$ws from $t to $next, at $m", ws.isOpen(m))
                m = m.plusMinutes(1)
            }
        }
    }

    @Test
    fun timesAre24HourWithAsciiDigits() {
        assertEquals("00:00", timeText(0))
        assertEquals("13:05", timeText(13 * 60 + 5))
        assertEquals("23:59", timeText(MINUTES_PER_DAY - 1))
    }

    /** Whatever is stored (an older or damaged value): no crash, only valid windows, at most 8. */
    @Test
    fun anyStoredWindowsReadSafely() {
        val r = Random(32)
        val pieces = listOf("[", "]", "{", "}", ",", ":", "\"start\"", "\"end\"", "\"days\"", "-1", "0", "1439", "1440", "7", "8", "null", "true", "\"x\"", "99999999999")
        repeat(20_000) {
            val stored = when (r.nextInt(3)) {
                0 -> List(r.nextInt(0, 30)) { pieces.random(r) }.joinToString("")
                1 -> Settings.json.encodeToString(List(r.nextInt(0, 12)) { SyncWindow(r.nextInt(-2, 1442), r.nextInt(-2, 1442), List(r.nextInt(0, 4)) { r.nextInt(-1, 9) }.toSet()) })
                else -> String(CharArray(r.nextInt(0, 40)) { r.nextInt(0, 0x3000).toChar() })
            }
            val ws = mutablePreferencesOf(Settings.TIME_SCHEDULE to true, Settings.WINDOWS to stored).runConditions().windows
            assertTrue(stored, ws.size <= MAX_SYNC_WINDOWS && ws.all { syncWindowError(it) == null })
        }
    }

    /** Property: what the form lets through (no [scheduleError]) is what the rules allow. */
    @Test
    fun theFormLetsThroughOnlyValidSchedules() {
        val r = Random(33)
        repeat(20_000) {
            val on = r.nextBoolean()
            val ws = List(r.nextInt(0, 10)) {
                SyncWindow(r.nextInt(-2, 1442), r.nextInt(-2, 1442), List(r.nextInt(0, 3)) { r.nextInt(0, 9) }.toSet())
            }
            val valid = ws.size <= MAX_SYNC_WINDOWS && (!on || ws.isNotEmpty()) && ws.all {
                it.start in 0..1439 && it.end in 0..1439 && it.start != it.end && it.days.isNotEmpty() && it.days.all { d -> d in 1..7 }
            }
            assertEquals("$on $ws", valid, scheduleError(on, ws) == null)
        }
    }
}
