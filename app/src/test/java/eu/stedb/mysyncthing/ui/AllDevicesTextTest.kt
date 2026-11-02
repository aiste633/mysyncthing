package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.DevicePause
import eu.stedb.mysyncthing.core.DeviceState
import eu.stedb.mysyncthing.core.PAUSED_BY_USER
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.SyncWindow
import eu.stedb.mysyncthing.core.blockedBy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random

/** The texts of the Devices tab that depend on the time, and when "Sync times" is in amber. */
class AllDevicesTextTest {
    private val now = ZonedDateTime.of(2026, 11, 2, 15, 45, 0, 0, ZoneOffset.UTC)

    @Test
    fun lastSeen() {
        assertEquals("Last seen 14:41", lastSeenText(Instant.parse("2026-11-02T14:41:00Z"), now).en())
        assertEquals("Last seen yesterday, 22:40", lastSeenText(Instant.parse("2026-11-01T22:40:00Z"), now).en())
        assertEquals("Last seen 28 Oct, 18:02", lastSeenText(Instant.parse("2026-10-28T18:02:00Z"), now).en())
        assertEquals("Last seen 3 Oct 2025, 18:42", lastSeenText(Instant.parse("2025-10-03T18:42:00Z"), now).en())
        assertEquals("Never connected", lastSeenText(null, now).en())
    }

    /** In the phone's zone: just after midnight there is yesterday before midnight UTC. */
    @Test
    fun lastSeenInThePhonesZone() {
        val rome = ZonedDateTime.of(2026, 11, 2, 0, 30, 0, 0, java.time.ZoneId.of("Europe/Rome"))
        assertEquals("Last seen 00:10", lastSeenText(Instant.parse("2026-11-01T23:10:00Z"), rome).en())
    }

    /**
     * Amber exactly when the sync times are what the run conditions pause the devices for:
     * the same answer as [blockedBy] on a network that allows everything.
     */
    @Test
    fun syncTimesAreAmberWhenTheyPause() {
        val r = Random(20261102)
        val free = DeviceState(online = true, wifi = true, unmetered = true)
        repeat(20_000) {
            val windows = List(r.nextInt(0, 4)) {
                SyncWindow(r.nextInt(0, 1440), r.nextInt(0, 1440), (1..7).filter { r.nextBoolean() }.toSet().ifEmpty { setOf(1) })
            }.filter { it.start != it.end }
            val rc = RunConditions(pausedByUser = r.nextInt(4) == 0, timeSchedule = r.nextBoolean() && windows.isNotEmpty(), windows = windows)
            val t = LocalDateTime.of(2026, 11, r.nextInt(1, 30), r.nextInt(0, 24), r.nextInt(0, 60))
            val reason = rc.blockedBy(free, t)
            // Applied: the service has paused the devices for the reason; not applied yet: nothing is in amber.
            val applied = r.nextInt(5) != 0
            val pause = DevicePause(if (applied) reason else null)
            val expected = applied && reason != null && reason != PAUSED_BY_USER
            assertEquals("$rc at $t", expected, scheduleBlocks(rc, pause, t))
        }
    }
}
