package eu.stedb.mysyncthing.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * No device connected: grey "Waiting for devices" for 30 min after the last device that is not
 * paused was seen, or after the start, syncing allowed again or a device resumed when that is
 * later (waitingSince), then red. The service summarizes
 * again then, as no event says the time is up.
 */
class NoDeviceTest {
    private val running = CoreState.Running(RestClient("127.0.0.1:1", "k"), "127.0.0.1:1", "ME", Credentials("k", "u", "p"))
    private val server = Device("SERVER1-X", name = "server")
    private val laptop = Device("LAPTOP1-X", name = "laptop")
    private val photos = Folder("photos", path = "/storage/emulated/0/DCIM")
    private val t0: Instant = Instant.parse("2026-11-02T14:41:00Z")

    private fun snap(devices: List<Device> = listOf(server, laptop), connected: Set<String> = emptySet(), seen: Map<String, Instant> = emptyMap()) = Snapshot(
        loaded = true, myId = "ME", devices = devices, folders = listOf(photos),
        folderStatus = mapOf(photos.id to FolderStatus(state = "idle")),
        connections = devices.associate { it.deviceID to Connection(connected = it.deviceID in connected) },
        lastSeen = seen,
    )

    private fun at(s: Snapshot, now: Instant, since: Instant) = summarize(running, s, null, true, now, since)

    /** The user's choice (2026-11-02): 30 min; the text names it. */
    @Test
    fun theLimitIsHalfAnHour() {
        assertEquals(Duration.ofMinutes(30), NO_DEVICE_LIMIT)
    }

    @Test
    fun halfAnHourOfWaitingThenAProblem() {
        val s = snap(seen = mapOf(server.deviceID to t0, laptop.deviceID to t0.minusSeconds(600)))
        val start = t0.minus(Duration.ofDays(1))
        at(s, t0.plus(Duration.ofMinutes(29)), start).let {
            assertEquals(SyncState.Waiting to "Waiting for devices", it.state to it.line().en())
            assertEquals(t0, it.lastSeen)
            assertEquals(t0.plus(NO_DEVICE_LIMIT), it.recheckAt)
        }
        at(s, t0.plus(NO_DEVICE_LIMIT), start).let {
            assertEquals(SyncState.Problem to "No device for over 30 min", it.state to it.line().en())
            assertEquals(t0, it.lastSeen)
            assertNull(it.recheckAt)
        }
    }

    @Test
    fun neverSeenCountsFromTheStart() {
        val s = snap()
        at(s, t0.plusSeconds(900), t0).let {
            assertEquals(SyncState.Waiting, it.state)
            assertNull(it.lastSeen)
            assertEquals(t0.plus(NO_DEVICE_LIMIT), it.recheckAt)
        }
        assertEquals(SyncState.Problem, at(s, t0.plus(Duration.ofHours(2)), t0).state)
    }

    /** A phone switched off for days gets its 30 min after the start, not a red status at once. */
    @Test
    fun theStartGivesAnHourAfterALongBreak() {
        val s = snap(seen = mapOf(server.deviceID to t0.minus(Duration.ofDays(3))))
        at(s, t0.plusSeconds(60), t0).let {
            assertEquals(SyncState.Waiting, it.state)
            assertEquals(t0.minus(Duration.ofDays(3)), it.lastSeen)
            assertEquals(t0.plus(NO_DEVICE_LIMIT), it.recheckAt)
        }
    }

    /** A device the user paused is not missed: only the others count, also for "last seen". */
    @Test
    fun pausedDevicesDoNotCount() {
        val s = snap(
            devices = listOf(server, laptop.copy(paused = true)),
            seen = mapOf(server.deviceID to t0.minus(Duration.ofHours(2)), laptop.deviceID to t0.minusSeconds(60)),
        )
        at(s, t0, t0.minus(Duration.ofDays(1))).let {
            assertEquals(SyncState.Problem, it.state)
            assertEquals(t0.minus(Duration.ofHours(2)), it.lastSeen)
        }
    }

    @Test
    fun oneConnectedDeviceIsEnough() {
        val s = snap(connected = setOf(laptop.deviceID))
        at(s, t0.plus(Duration.ofDays(9)), t0).let {
            assertEquals(SyncState.UpToDate, it.state)
            assertNull(it.recheckAt)
            assertNull(it.lastSeen)
        }
    }

    /** Pauses and the run conditions rank first: they say why nothing connects. */
    @Test
    fun pausesWinOverTheWait() {
        val late = t0.plus(Duration.ofDays(2))
        assertEquals(SyncState.Paused, at(snap(devices = listOf(server.copy(paused = true))), late, t0).state)
        assertEquals(SyncState.Paused, summarize(running, snap(), raw("Not on Wi-Fi"), true, late, t0).state)
        assertEquals(SyncState.Problem to "No network", summarize(running, snap(), null, false, late, t0).let { it.state to it.text.en() })
    }

    @Test
    fun property() {
        val r = Random(20261102)
        repeat(5_000) {
            val devices = List(r.nextInt(1, 5)) { i -> Device("DEV$i-X", name = "d$i", paused = r.nextInt(3) == 0) }
                .let { ds -> if (ds.all { it.paused }) ds.mapIndexed { i, d -> if (i == 0) d.copy(paused = false) else d } else ds }
            val start = t0.plusSeconds(r.nextLong(-86_400, 86_400))
            val seen = devices.filter { r.nextBoolean() }.associate { it.deviceID to t0.plusSeconds(r.nextLong(-200_000, 86_400)) }
            val now = maxOf(start, seen.values.maxOrNull() ?: start).plusSeconds(r.nextLong(0, 3 * 3600))
            val sum = at(snap(devices, seen = seen), now, start)

            val last = devices.filter { !it.paused }.mapNotNull { seen[it.deviceID] }.maxOrNull()
            val due = maxOf(last ?: start, start).plus(NO_DEVICE_LIMIT)
            assertEquals(last, sum.lastSeen)
            if (now >= due) {
                assertEquals(SyncState.Problem, sum.state)
                assertNull(sum.recheckAt)
            } else {
                assertEquals(SyncState.Waiting, sum.state)
                assertEquals(due, sum.recheckAt)
                assertTrue(sum.recheckAt!! > now)
                // At the recheck the wait has become a problem.
                assertEquals(SyncState.Problem, at(snap(devices, seen = seen), due, start).state)
            }
        }
    }

    @Test
    fun widgetSaysWhenTheLastDeviceWasSeen() {
        val now = ZonedDateTime.ofInstant(t0.plusSeconds(3600), ZoneOffset.UTC)
        fun detail(s: Summary) = widgetDetail(s, now).en()
        val waiting = at(snap(seen = mapOf(server.deviceID to t0)), t0.plusSeconds(60), t0)
        assertEquals("Last device seen 14:41", detail(waiting))
        assertEquals("Last device seen yesterday, 14:41", detail(waiting.copy(lastSeen = t0.minus(Duration.ofDays(1)))))
        assertEquals("No device connected", detail(at(snap(), t0.plusSeconds(60), t0)))
        assertEquals("server", detail(at(snap(connected = setOf(server.deviceID)), t0, t0)))
    }

    @Test
    fun restTimesBecomeInstants() {
        assertEquals(t0, instantOf("2026-11-02T15:41:00+01:00"))
        assertEquals(Instant.parse("2026-11-02T14:41:00.123456789Z"), instantOf("2026-11-02T14:41:00.123456789Z"))
        assertNull(instantOf("0001-01-01T00:00:00Z")) // the core's "never"
        assertNull(instantOf("1970-01-01T00:00:00Z"))
        assertNull(instantOf(""))
        assertNull(instantOf("yesterday"))
    }

    /** A waiting summary comes again at its recheck, by the wall clock; one that waits for nothing comes once. */
    @Test
    fun timedSummarizesAgainAtTheRecheck() = runBlocking {
        var clock = t0
        val waits = mutableListOf<Instant>()
        val s = snap()
        val out = flowOf { now: Instant -> at(s, now, t0) }
            .timed({ clock }) { waits += it; clock = it }
            .toList()
        assertEquals(listOf(SyncState.Waiting, SyncState.Problem), out.map { it.state })
        assertEquals(listOf(t0.plus(NO_DEVICE_LIMIT)), waits)
    }

    /** A new snapshot while waiting replaces the wait: a device that connects ends it. */
    @Test
    fun aNewSnapshotCancelsTheWait() = runBlocking {
        val inputs = Channel<(Instant) -> Summary>(Channel.UNLIMITED)
        val parked = Channel<Instant>(Channel.UNLIMITED)
        val results = Channel<Summary>(Channel.UNLIMITED)
        val job = launch {
            inputs.consumeAsFlow().timed({ t0 }) { parked.send(it); kotlinx.coroutines.awaitCancellation() }.collect { results.send(it) }
        }
        inputs.send { now -> at(snap(), now, t0) }
        assertEquals(SyncState.Waiting, results.receive().state)
        assertEquals(t0.plus(NO_DEVICE_LIMIT), parked.receive())
        inputs.send { now -> at(snap(connected = setOf(server.deviceID)), now, t0) }
        assertEquals(SyncState.UpToDate, results.receive().state)
        inputs.close()
        job.join()
        assertTrue(results.tryReceive().isFailure)
    }

    /** Steps of (syncing allowed, devices not paused) at their times, through [waitingSince]. */
    private fun since(vararg steps: Pair<Instant, Pair<Boolean, Set<String>>>): List<Instant> = runBlocking {
        var clock = t0
        val inputs = Channel<Pair<Boolean, Set<String>>>(Channel.UNLIMITED)
        steps.forEach { inputs.send(it.second) }
        inputs.close()
        var i = 0
        inputs.consumeAsFlow().map { v -> clock = steps[i++].first; v }.waitingSince(t0) { clock }.toList()
    }

    private val both = setOf(server.deviceID, laptop.deviceID)

    @Test
    fun theWaitStartsAgainWhenADeviceIsResumed() {
        val (t1, t2, t3) = listOf(1L, 2L, 3L).map { t0.plus(Duration.ofHours(it)) }
        // Loaded, laptop paused by the user, laptop resumed hours later.
        assertEquals(
            listOf(t0, t1, t3),
            since(t1 to (true to both), t2 to (true to setOf(server.deviceID)), t3 to (true to both)),
        )
    }

    @Test
    fun theWaitStartsAgainWhenSyncingIsAllowedAgain() {
        val (t1, t2, t3, t4) = listOf(1L, 2L, 3L, 4L).map { t0.plus(Duration.ofHours(it)) }
        assertEquals(
            listOf(t0, t1, t4),
            // Blocked (the run conditions pause every device), then allowed, then the devices resumed.
            since(t1 to (true to both), t2 to (false to both), t3 to (false to emptySet()), t4 to (true to emptySet()), t4 to (true to both)),
        )
    }

    @Test
    fun aDevicePausedOrResumedWhileBlockedWaitsForTheEndOfTheBlock() {
        val (t1, t2, t3) = listOf(1L, 2L, 3L).map { t0.plus(Duration.ofHours(it)) }
        assertEquals(listOf(t0, t3), since(t1 to (false to emptySet()), t2 to (false to both), t3 to (true to both)))
    }

    /** The finding (2026-11-02): resumed after days away, the device gets its 30 min, then red. */
    @Test
    fun aDeviceResumedAfterDaysIsWaitedForFirst() {
        val resumed = t0.plus(Duration.ofDays(3))
        val s = snap(seen = mapOf(server.deviceID to t0, laptop.deviceID to t0))
        at(s, resumed.plusSeconds(60), resumed).let {
            assertEquals(SyncState.Waiting, it.state)
            assertEquals(t0, it.lastSeen)
            assertEquals(resumed.plus(NO_DEVICE_LIMIT), it.recheckAt)
        }
        assertEquals(SyncState.Problem, at(s, resumed.plus(NO_DEVICE_LIMIT), resumed).state)
    }

    /** Against a plain model: the wait restarts exactly at an allowed step that ends a block or adds a device. */
    @Test
    fun waitingSinceProperty() {
        val r = Random(2026110216)
        val ids = List(4) { "DEV$it-X" }
        repeat(2_000) {
            val steps = List(r.nextInt(1, 12)) { i ->
                t0.plusSeconds(600L * (i + 1)) to ((r.nextInt(4) != 0) to ids.filter { r.nextBoolean() }.toSet())
            }.toTypedArray()
            var expected = t0
            var allowed = true
            var unpaused = emptySet<String>()
            val model = mutableListOf(t0)
            for ((at, v) in steps) {
                if (v.first && (!allowed || !unpaused.containsAll(v.second))) expected = at
                allowed = v.first
                unpaused = v.second
                if (expected != model.last()) model += expected
            }
            val got = since(*steps)
            assertEquals(model, got)
            assertEquals(got.sorted(), got)
        }
    }
}
