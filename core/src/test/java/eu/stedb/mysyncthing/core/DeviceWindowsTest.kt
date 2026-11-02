package eu.stedb.mysyncthing.core

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** Devices' own sync windows: what they allow together with the global ones, and what holds a device. */
class DeviceWindowsTest {
    /** Monday 2026-10-26, so day d of that week is 2026-10-(25 + d). */
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 25, hour, minute).plusDays(day.toLong())
    private val monday = at(1, 0)
    private val nights = listOf(SyncWindow(22 * 60, 7 * 60, (1..5).toSet()))
    private val global = RunConditions(timeSchedule = true, windows = nights)
    private val running = CoreState.Running(RestClient("127.0.0.1:1", "k"), "127.0.0.1:1", "ME", Credentials("k", "u", "p"))

    private fun randomWindows(r: Random) = List(r.nextInt(0, MAX_SYNC_WINDOWS + 1)) {
        val start = r.nextInt(MINUTES_PER_DAY)
        // Short windows too, so stretches under 1 h and single minutes come up.
        val end = (start + if (r.nextBoolean()) r.nextInt(1, 90) else r.nextInt(1, MINUTES_PER_DAY)) % MINUTES_PER_DAY
        SyncWindow(start, end, (1..7).filter { r.nextInt(3) == 0 }.toSet().ifEmpty { setOf(r.nextInt(1, 8)) })
    }

    /** The mask answers as the windows do for every minute of the week, and its stretches add up to it. */
    @Test
    fun theMaskIsTheWindowsMinuteByMinute() {
        val r = Random(20261102)
        repeat(300) {
            val ws = randomWindows(r)
            val m = WeekMask.of(ws)
            var open = 0
            for (k in 0 until MINUTES_PER_WEEK) {
                val t = monday.plusMinutes(k.toLong())
                assertEquals("$ws at $t", ws.isOpen(t), m.isOpen(t))
                if (ws.isOpen(t)) open++
            }
            assertEquals(open, m.minutes)
            assertEquals(ws.isEmpty(), m.isEmpty)
            val stretches = m.stretches()
            assertEquals(open, stretches.sumOf { it.length })
            // Each stretch is open throughout and closed just before and after (unless it is the whole week).
            for (s in stretches) {
                if (s.length == MINUTES_PER_WEEK) continue
                assertFalse(m.isOpen(monday.plusMinutes(((s.start - 1 + MINUTES_PER_WEEK) % MINUTES_PER_WEEK).toLong())))
                assertFalse(m.isOpen(monday.plusMinutes(s.end.toLong())))
                assertTrue((0 until s.length).all { m.isOpen(monday.plusMinutes(((s.start + it) % MINUTES_PER_WEEK).toLong())) })
            }
        }
    }

    /** The next open minute is the first one after now, searched minute by minute; across Sunday midnight too. */
    @Test
    fun nextOpenIsTheFirstOpenMinute() {
        val r = Random(7)
        repeat(200) {
            val m = WeekMask.of(randomWindows(r))
            val now = monday.plusMinutes(r.nextLong(MINUTES_PER_WEEK.toLong())).plusSeconds(r.nextLong(60))
            val expected = (1..MINUTES_PER_WEEK).map { now.withSecond(0).plusMinutes(it.toLong()) }.firstOrNull { m.isOpen(it) }
            assertEquals(expected, m.nextOpen(now))
        }
        // Friday night into Saturday morning, then nothing until Monday night.
        assertEquals(at(8, 22), WeekMask.of(nights).nextOpen(at(6, 12)))
    }

    /** A stretch across Sunday midnight is one, not two. */
    @Test
    fun aStretchAcrossTheWeekEndIsOne() {
        val m = WeekMask.of(listOf(SyncWindow(23 * 60, 60, setOf(7))))
        assertEquals(listOf(Stretch(6 * MINUTES_PER_DAY + 23 * 60, 120)), m.stretches())
        assertEquals(60, m.stretches().single().end)
        assertEquals(listOf(Stretch(0, MINUTES_PER_WEEK)), WeekMask.ALWAYS.stretches())
        assertEquals(emptyList<Stretch>(), WeekMask.of(emptyList()).stretches())
    }

    /** The mockup's cases: global Mon–Fri 22:00–07:00. */
    @Test
    fun ownWindowsNarrowTheGlobalOnes() {
        val overlap = global.withDeviceWindows("NAS", DeviceWindows(true, listOf(SyncWindow(21 * 60, 23 * 60, (1..5).toSet()))))
        assertEquals(5 * 60, overlap.mask("NAS").minutes)
        assertTrue(overlap.mask("NAS").shortStretches().isEmpty())
        assertEquals(global.globalMask(), overlap.mask("Laptop"))

        val short = global.withDeviceWindows("NAS", DeviceWindows(true, listOf(SyncWindow(6 * 60 + 30, 9 * 60, (1..5).toSet()))))
        // Monday 06:30–07:00 is closed: no Sunday-night window.
        assertEquals(4, short.mask("NAS").shortStretches().size)
        assertEquals(2 * 60, short.mask("NAS").minutes)

        val never = global.withDeviceWindows("NAS", DeviceWindows(true, listOf(SyncWindow(9 * 60, 17 * 60, setOf(6, 7)))))
        assertTrue(never.mask("NAS").isEmpty)
        assertEquals(setOf("NAS"), never.neverSyncing())
        // Off, or with the global windows off, it syncs.
        assertEquals(emptySet<String>(), never.withDeviceWindows("NAS", DeviceWindows(false, never.deviceWindows["NAS"]!!.windows)).neverSyncing())
        assertEquals(emptySet<String>(), never.copy(timeSchedule = false).neverSyncing())
        // An entry that says nothing is dropped.
        assertEquals(emptyMap<String, DeviceWindows>(), never.withDeviceWindows("NAS", DeviceWindows()).deviceWindows)
    }

    @Test
    fun aDeviceIsHeldOutsideWhatBothAllow() {
        val c = global.withDeviceWindows("NAS", DeviceWindows(true, listOf(SyncWindow(21 * 60, 23 * 60, (1..5).toSet()))))
            .withDeviceWindows("Tablet", DeviceWindows(true, listOf(SyncWindow(9 * 60, 17 * 60, setOf(6, 7)))))
            .withDeviceWindows("Off", DeviceWindows(false, listOf(SyncWindow(9 * 60, 10 * 60, setOf(1)))))
        // Monday 21:30: the global windows are closed; NAS's own are open, but it really syncs at 22:00.
        val evening = c.deviceHolds(at(1, 21, 30))
        assertEquals(setOf("NAS", "Tablet"), evening.keys)
        assertEquals("Outside its sync windows · next 22:00", evening.getValue("NAS").reason.en())
        assertEquals(at(1, 22), evening.getValue("NAS").next)
        assertTrue(evening.getValue("Tablet").never)
        assertEquals("Never syncs: its windows don't overlap the global ones", evening.getValue("Tablet").reason.en())
        // 22:30: NAS syncs; 23:30 it waits for Tuesday.
        assertEquals(setOf("Tablet"), c.deviceHolds(at(1, 22, 30)).keys)
        assertEquals("Outside its sync windows · next Tue 22:00", c.deviceHolds(at(1, 23, 30)).getValue("NAS").reason.en())
        // One clock for all: the global windows and every own window that is on.
        assertEquals(nights + c.deviceWindows.getValue("NAS").windows + c.deviceWindows.getValue("Tablet").windows, c.clockWindows())
    }

    /** What the cards show: never syncing first, then a reason for all, but the device's own "next" over the global windows'. */
    @Test
    fun theReasonForADevice() {
        val nas = Device(deviceID = "NAS", paused = true)
        val own = Hold(raw("own"), at(1, 22))
        val never = Hold(raw("never"), never = true)
        assertEquals("own", DevicePause(raw("schedule"), devices = mapOf("NAS" to own), bySchedule = true).reason(nas)?.en())
        assertEquals("mobile", DevicePause(raw("mobile"), devices = mapOf("NAS" to own)).reason(nas)?.en())
        assertEquals("never", DevicePause(raw("mobile"), devices = mapOf("NAS" to never)).reason(nas)?.en())
        assertEquals("schedule", DevicePause(raw("schedule"), bySchedule = true).reason(nas)?.en())
        assertNull(DevicePause().reason(nas))
    }

    /** Every device paused, some by their own windows: the status says when the first of those syncs again. */
    @Test
    fun pausedSaysWhenTheFirstDeviceSyncsAgain() {
        val now = at(1, 12).atZone(ZoneId.systemDefault()).toInstant()
        val s = Snapshot(
            loaded = true, devices = listOf(Device(deviceID = "A", paused = true), Device(deviceID = "B", paused = true), Device(deviceID = "C", paused = true)),
            folders = listOf(Folder(id = "f")),
            pause = DevicePause(
                holds = ConditionHolds(setOf("A", "B"), setOf("A")),
                devices = mapOf("A" to Hold(raw("a"), at(1, 18)), "B" to Hold(raw("b"), at(1, 22))),
            ),
        )
        // A is paused by the user too: B's 22:00 counts.
        assertEquals("Paused · next 22:00", summarize(running, s, null, true, now).text.en())
        val later = s.copy(pause = s.pause.copy(devices = mapOf("B" to Hold(raw("b"), at(3, 7)))))
        assertEquals("Paused · next Wed 07:00", summarize(running, later, null, true, now).text.en())
        assertEquals("Paused", summarize(running, s.copy(pause = DevicePause()), null, true, now).text.en())
    }

    @Test
    fun ownWindowsAreStoredAndBadOnesLeftOut() = runBlocking {
        val store = FillableStore()
        val settings = Settings(store)
        val w = SyncWindow(21 * 60, 23 * 60, (1..5).toSet())
        assertTrue(settings.update { it.withDeviceWindows("NAS", DeviceWindows(true, listOf(w))) })
        assertEquals(mapOf("NAS" to DeviceWindows(true, listOf(w))), settings.runConditions.first().deviceWindows)
        store.edit {
            it[Settings.DEVICE_WINDOWS] = """{"NAS":{"on":true,"windows":[{"start":5,"end":5,"days":[1]},{"start":60,"end":120,"days":[2]}]},"Bad":7}"""
        }
        // The unreadable map is dropped as a whole: every device follows the global windows.
        assertEquals(emptyMap<String, DeviceWindows>(), settings.runConditions.first().deviceWindows)
        store.edit { it[Settings.DEVICE_WINDOWS] = """{"NAS":{"on":true,"windows":[{"start":5,"end":5,"days":[1]},{"start":60,"end":120,"days":[2]}]}}""" }
        assertEquals(listOf(SyncWindow(60, 120, setOf(2))), settings.runConditions.first().deviceWindows.getValue("NAS").windows)
        // None left: then not even the stored entry keeps a key that says nothing.
        assertTrue(settings.update { it.withDeviceWindows("NAS", DeviceWindows()) })
        assertFalse(store.data.first().contains(Settings.DEVICE_WINDOWS))
    }
}
