package eu.stedb.mysyncthing.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * [SyncStatus.follow] against a fake core: what it asks the core, in which order, and what the
 * snapshot then holds. The fake answers the event long-poll only when the test says so, so each
 * step is checked while the follower waits.
 */
class SyncStatusFollowTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A core that answers every path in [answers] with its JSON, 404 for the rest, and hands each event poll to the test. */
    private class FakeCore : AutoCloseable {
        private val pool = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val answers = ConcurrentHashMap<String, String>()

        /** Every request but the event polls, as "METHOD path?query", in the order they came. */
        val requests = CopyOnWriteArrayList<String>()
        private val polls = LinkedBlockingQueue<HttpExchange>()
        val address get() = "127.0.0.1:${server.address.port}"

        init {
            server.executor = pool
            server.createContext("/") { x ->
                val uri = x.requestURI
                if (uri.path == "/rest/events") {
                    polls.put(x)
                    return@createContext
                }
                val key = uri.path.removePrefix("/") + uri.rawQuery?.let { "?$it" }.orEmpty()
                requests += "${x.requestMethod} $key"
                val body = if (x.requestMethod == "POST") "" else answers[key]
                reply(x, if (body == null) 404 else 200, body.orEmpty())
            }
            server.start()
        }

        fun count(request: String) = requests.count { it == request }

        /** The next event poll, which stays open until [Poll.answer]; fails when none comes. */
        fun poll(): Poll = Poll(checkNotNull(polls.poll(5, TimeUnit.SECONDS)) { "no event poll within 5 s" })

        fun noPollFor(ms: Long) = assertEquals("an event poll", null, polls.poll(ms, TimeUnit.MILLISECONDS)?.requestURI)

        inner class Poll(private val x: HttpExchange) {
            private val query = x.requestURI.query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val since get() = query.getValue("since").toLong()
            val timeout get() = query.getValue("timeout").toInt()
            val types get() = query["events"]?.split(',')?.toSet()

            fun answer(events: String = "[]") = reply(x, 200, events)
            fun fail() = reply(x, 500, "no")
        }

        private fun reply(x: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray()
            x.responseHeaders.add("Content-Type", "application/json")
            x.sendResponseHeaders(code, bytes.size.toLong().takeIf { it > 0 } ?: -1)
            if (bytes.isNotEmpty()) x.responseBody.use { it.write(bytes) }
            x.close()
        }

        override fun close() {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    /** The follower's waits: a wait of [held] ms blocks until [release]; any other returns at once. */
    private class Waits(private val held: Long?) {
        val asked = CopyOnWriteArrayList<Long>()
        private val gate = Channel<Unit>(Channel.UNLIMITED)
        suspend fun pause(ms: Long) {
            asked += ms
            if (ms == held) gate.receive()
        }
        fun release() = check(gate.trySend(Unit).isSuccess)
    }

    private class Run(val core: FakeCore, val waits: Waits, val clock: AtomicLong)

    /** Runs [test] while the follower follows a fake core with one folder at [folderPath] and two devices besides this one. */
    private fun following(folderPath: String = "/nowhere", held: Long? = null, test: suspend CoroutineScope.(Run) -> Unit) = runBlocking {
        FakeCore().use { core ->
            core.answers += mapOf(
                "rest/system/status" to """{"myID":"ME"}""",
                "rest/config/devices" to """[{"deviceID":"ME","name":"phone"},{"deviceID":"A","name":"laptop"},{"deviceID":"B","paused":true}]""",
                "rest/config/folders" to """[{"id":"f1","label":"Photos","path":${JsonPrimitive(folderPath)}}]""",
                "rest/system/connections" to """{"connections":{"A":{"connected":true},"B":{"connected":false}}}""",
                "rest/cluster/pending/devices" to """{"C":{"name":"new"}}""",
                "rest/cluster/pending/folders" to """{"f9":{"offeredBy":{"A":{"label":"Music"}}}}""",
                "rest/system/error" to """{"errors":null}""",
                "rest/config/options" to """{"localAnnounceEnabled":false,"globalAnnounceEnabled":true}""",
                "rest/stats/device" to """{"A":{"lastSeen":"2026-11-01T10:00:00Z"},"B":{"lastSeen":"1970-01-01T00:00:00Z"}}""",
                "rest/db/status?folder=f1" to """{"state":"syncing","needBytes":7}""",
                "rest/db/completion?device=A" to """{"completion":50.0,"needBytes":3}""",
                "rest/db/completion?device=B" to """{"completion":100.0}""",
            )
            val run = Run(core, Waits(held), AtomicLong(0))
            val follower = launch(Dispatchers.Default) {
                SyncStatus.follow(RestClient.plain(core.address, "k"), elapsed = run.clock::get, pause = run.waits::pause)
            }
            try {
                withTimeout(30_000) { test(run) }
            } finally {
                follower.cancelAndJoin()
            }
            assertEquals("a stopped follower leaves an empty snapshot", Snapshot(), SyncStatus.snapshot.value)
            assertEquals(emptyMap<String, Map<String, FileProgress>>(), SyncStatus.downloads.value)
        }
    }

    private suspend fun <T : Any> within5s(what: () -> String, block: suspend () -> T): T =
        withTimeoutOrNull(5_000) { block() } ?: throw AssertionError("${what()}: not within 5 s")

    private suspend fun snapshot(what: String, test: (Snapshot) -> Boolean): Snapshot =
        within5s({ "$what; the snapshot is ${SyncStatus.snapshot.value}" }) { SyncStatus.snapshot.first(test) }

    /**
     * Waits until the core saw [n] loads, then a moment more for one too many. A load is counted by
     * [request], which every load makes exactly once.
     */
    private suspend fun FakeCore.loads(n: Int, request: String = STATUS, settle: Boolean = true) {
        within5s({ "$n loads, ${count(request)} seen" }) { while (count(request) < n) delay(10) }
        if (settle) delay(SETTLE_MS)
        assertEquals("loads", n, count(request))
    }

    /** Opens the stream and waits for the first load. */
    private suspend fun Run.started(): Snapshot {
        core.poll().answer()
        return snapshot("first load") { it.loaded }
    }

    private fun event(id: Long, type: String, data: String = "null") = """{"id":$id,"type":"$type","data":$data}"""

    @Test
    fun theStreamIsOpenedBeforeTheFirstLoad() = following { r ->
        val first = r.core.poll()
        assertEquals(0, first.since)
        assertEquals("the first poll returns at once", 0, first.timeout)
        assertEquals(FOLLOW_EVENTS, first.types)
        // The core makes the stream when this request arrives: until then nothing may be read.
        delay(SETTLE_MS)
        assertEquals("requests before the stream is open", emptyList<String>(), r.core.requests.toList())
        assertFalse(SyncStatus.snapshot.value.loaded)

        first.answer()
        snapshot("first load") { it.loaded }
        val second = r.core.poll()
        assertEquals(0, second.since)
        assertEquals("then it waits for events", 60, second.timeout)
        assertEquals(FOLLOW_EVENTS, second.types)
        r.core.loads(1)
    }

    @Test
    fun aLoadReadsEverythingOnce() = following { r ->
        val s = r.started()
        assertEquals("ME", s.myId)
        assertEquals("phone", s.myName)
        assertEquals("this phone is not among its devices", listOf("A", "B"), s.devices.map { it.deviceID })
        assertEquals(listOf("f1"), s.folders.map { it.id })
        assertEquals(mapOf("f1" to FolderStatus(state = "syncing", needBytes = 7)), s.folderStatus)
        assertEquals(setOf("A", "B"), s.connections.keys)
        assertTrue(s.connections.getValue("A").connected)
        assertEquals(mapOf("A" to Completion(50.0, 3), "B" to Completion(100.0)), s.completion)
        assertEquals(mapOf("C" to PendingDevice(name = "new")), s.pendingDevices)
        assertEquals(setOf("f9"), s.pendingFolders.keys)
        assertEquals(emptyList<SystemError>(), s.systemErrors)
        assertFalse(s.localDiscovery)
        assertTrue(s.globalDiscovery)
        assertEquals("a device never seen has no time", mapOf("A" to Instant.parse("2026-11-01T10:00:00Z")), s.lastSeen)
        r.core.loads(1)
        assertEquals(
            "one load: nine calls, one per folder, one per other device",
            listOf(
                "GET rest/cluster/pending/devices", "GET rest/cluster/pending/folders", "GET rest/config/devices",
                "GET rest/config/folders", "GET rest/config/options", "GET rest/db/completion?device=A",
                "GET rest/db/completion?device=B", "GET rest/db/status?folder=f1", "GET rest/stats/device",
                "GET rest/system/connections", "GET rest/system/error", STATUS,
            ),
            r.core.requests.sorted(),
        )
    }

    @Test
    fun aLoadSurvivesTheCallsThatMayFail() = following { r ->
        listOf("rest/system/error", "rest/stats/device", "rest/db/status?folder=f1", "rest/db/completion?device=A").forEach(r.core.answers::remove)
        val s = r.started()
        assertEquals(emptyList<SystemError>(), s.systemErrors)
        assertEquals(emptyMap<String, Instant>(), s.lastSeen)
        assertEquals(mapOf("f1" to FolderStatus()), s.folderStatus)
        assertEquals(mapOf("A" to Completion(), "B" to Completion(100.0)), s.completion)
    }

    @Test
    fun aLoadThatFailsKeepsTheOldSnapshot() = following { r ->
        val before = r.started()
        r.core.answers.remove("rest/config/folders")
        r.core.poll().answer("[${event(1, "ConfigSaved")}]")
        r.core.loads(2, "GET rest/config/folders")
        assertEquals(before, SyncStatus.snapshot.value)
    }

    @Test
    fun onlyRefreshEventsReload() = following { r ->
        r.started()
        SyncStatus.publishPause(DevicePause(bySchedule = true))
        r.core.answers["rest/db/status?folder=f1"] = """{"state":"idle"}"""

        r.core.poll().answer("[${event(4, "ItemFinished")},${event(5, "StateChanged")}]")
        val s = snapshot("reload after StateChanged") { it.folderStatus["f1"]?.state == "idle" }
        assertEquals("the service's pause record survives a reload", DevicePause(bySchedule = true), s.pause)
        r.core.loads(2)

        val next = r.core.poll()
        assertEquals("the poll goes on after the last event", 5, next.since)
        assertEquals(60, next.timeout)
        // Asked for, but no reason to load: index updates and download progress.
        next.answer("[${event(6, "LocalIndexUpdated", """{"folder":"f1","filenames":["a.txt"]}""")},${event(7, "DownloadProgress", "{}")}]")
        assertEquals(7, r.core.poll().since)
        r.core.loads(2)
    }

    @Test
    fun everyRefreshEventReloads() = following { r ->
        r.started()
        REFRESH_EVENTS.forEachIndexed { i, type ->
            r.core.poll().answer("[${event(i + 1L, type)}]")
            r.core.loads(i + 2, settle = false)
        }
        r.core.loads(REFRESH_EVENTS.size + 1)
    }

    @Test
    fun atMostOneLoadASecond() = following(held = 1_000) { r ->
        r.started()
        r.core.loads(1)
        // The loader now waits its second out; events keep coming.
        for (id in 1L..3L) r.core.poll().answer("[${event(id, "FolderSummary")}]")
        assertEquals(3, r.core.poll().since)
        r.core.loads(1)
        r.waits.release()
        r.core.loads(2) // one load for the three events
        r.waits.release()
        r.core.loads(2) // and none after it
        assertEquals("a second after each load", listOf(1_000L, 1_000L), r.waits.asked.toList())
    }

    @Test
    fun downloadProgressIsTheLastOfABatch() = following { r ->
        r.started()
        val older = event(1, "DownloadProgress", """{"f1":{"a.txt":{"bytesDone":1,"bytesTotal":9}}}""")
        val newer = event(2, "DownloadProgress", """{"f1":{"a.txt":{"bytesDone":5,"bytesTotal":9}}}""")
        r.core.poll().answer("[$older,$newer]")
        val seen = within5s({ "download progress" }) { SyncStatus.downloads.first { it.isNotEmpty() } }
        assertEquals(mapOf("f1" to mapOf("a.txt" to FileProgress(5, 9))), seen)
        r.core.poll().answer("[${event(3, "DownloadProgress", "{}")}]")
        within5s({ "no downloads" }) { SyncStatus.downloads.first { it.isEmpty() } }
        r.core.loads(1)
    }

    @Test
    fun conflictCopiesFollowTheIndexUpdates() {
        val old = "notes.sync-conflict-20261101-100000-ABCDEFG.txt"
        val new = "sub/notes.sync-conflict-20261102-100000-ABCDEFG.txt"
        File(tmp.root, old).writeText("x")
        following(folderPath = tmp.root.path) { r ->
            r.started()
            snapshot("the folder is walked after the first load") { it.conflicts == mapOf("f1" to listOf(old)) }

            File(tmp.root, "sub").mkdir()
            File(tmp.root, new).writeText("y")
            r.core.poll().answer("[${event(1, "LocalIndexUpdated", """{"folder":"f1","filenames":["sub/a.txt","$new"]}""")}]")
            snapshot("a copy the core reports") { it.conflicts == mapOf("f1" to listOf(old, new)) }

            File(tmp.root, old).delete()
            r.core.poll().answer("[${event(2, "LocalIndexUpdated", """{"folder":"f1","filenames":["$old"]}""")}]")
            snapshot("a copy deleted") { it.conflicts == mapOf("f1" to listOf(new)) }
            r.core.loads(1)

            r.core.poll().answer("[${event(3, "FolderSummary")}]")
            r.core.loads(2)
            assertEquals("the copies survive a reload", mapOf("f1" to listOf(new)), SyncStatus.snapshot.value.conflicts)
        }
    }

    @Test
    fun noticesNeedNoEventAndNoLoad() = following { r ->
        r.started()
        r.core.answers["rest/system/error"] = """{"errors":[{"when":"2026-11-01T10:00:00Z","message":"port in use"}]}"""

        r.clock.set(30_000)
        r.core.poll().answer()
        val open = r.core.poll() // the follower is in its next poll, so the check before it is done
        assertEquals("not due yet: read by the first load only", 1, r.core.count(ERRORS))

        r.clock.set(30_001)
        open.answer()
        val s = snapshot("the notice") { it.systemErrors.isNotEmpty() }
        assertEquals("port in use", s.systemErrors.single().message)
        assertEquals(2, r.core.count(ERRORS))

        // Half a minute counts from that read.
        r.clock.set(60_000)
        r.core.poll().answer()
        r.core.poll()
        assertEquals(2, r.core.count(ERRORS))
        r.core.loads(1)

        SyncStatus.clearErrors(RestClient.plain(r.core.address, "k"))
        assertEquals(1, r.core.count("POST rest/system/error/clear"))
        assertEquals("cleared at once, before the next check", emptyList<SystemError>(), SyncStatus.snapshot.value.systemErrors)
    }

    @Test
    fun afterAnErrorTheStreamIsOpenedAgain() = following(held = 5_000) { r ->
        r.started()
        r.core.poll().answer("[${event(41, "FolderSummary")}]")
        r.core.loads(2)
        val broken = r.core.poll()
        assertEquals(41, broken.since)

        broken.fail()
        r.core.noPollFor(SETTLE_MS) // it waits before it tries again
        assertEquals(listOf(5_000L), r.waits.asked.filter { it != 1_000L })
        r.core.loads(2)
        r.waits.release()

        // A core that restarted in place counts from 1 again, in a stream it has yet to make.
        val again = r.core.poll()
        assertEquals(0, again.since)
        assertEquals("the stream is opened before the load that counts", 0, again.timeout)
        assertEquals(FOLLOW_EVENTS, again.types)
        r.core.loads(3) // the load at once, as before the stream was filtered
        again.answer()
        r.core.loads(4) // the load after the stream is open
        val next = r.core.poll()
        assertEquals(0, next.since)
        assertEquals(60, next.timeout)
    }

    @Test
    fun theFollowedEventsAreTheOnesTheCoreIsCheckedFor() {
        // The bridge's own test (TestFollowEventsContract) checks the core knows each name in the same file.
        val names = Json.parseToJsonElement(File("../go/bridge/testdata/follow-events.json").readText()).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(FOLLOW_EVENTS, names.toSet())
        assertEquals("a name twice", names.size, names.toSet().size)
        assertEquals(REFRESH_EVENTS + setOf("LocalIndexUpdated", "DownloadProgress"), FOLLOW_EVENTS)
        assertEquals(15, FOLLOW_EVENTS.size)
    }

    private companion object {
        const val STATUS = "GET rest/system/status"
        const val ERRORS = "GET rest/system/error"

        /** Long enough for a request the follower should not make to arrive all the same. */
        const val SETTLE_MS = 300L
    }
}
