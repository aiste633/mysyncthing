package eu.stedb.mysyncthing.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The import against service starts (rotation, boot, the app opening): a start
 * must see the old setup or the new one with its settings, never what is in
 * between (found by the export work: a rotation restarts the service mid-import).
 */
class ReplaceSetupTest {
    private companion object {
        const val OLD = 0
        const val STOPPED = 1
        const val INSTALLED = 2
        const val SETTINGS = 3
        const val DONE = 4
    }

    /** Steps that move [phase] on, with optional pauses to widen the windows between them. */
    private class Steps(
        val phase: AtomicInteger,
        val events: MutableList<String>,
        val pause: () -> Long = { 0 },
        val failInstall: String? = null,
        val removed: Boolean = true,
        val onInstall: () -> Unit = {},
    ) : SetupReplacement {
        override suspend fun stopCore() { delay(pause()); step(STOPPED, "stop") }
        override fun install() {
            onInstall()
            Thread.sleep(pause())
            failInstall?.let { error(it) }
            step(INSTALLED, "install")
        }
        override suspend fun applySettings() { delay(pause()); step(SETTINGS, "settings") }
        override fun removeBackup(): Boolean { step(DONE, "remove"); return removed }
        override fun startCore() { events += "start requested" }
        private fun step(p: Int, name: String) { phase.set(p); events += name }
    }

    /** What the service does: under the lock, look at the setup and start the core. */
    private suspend fun serviceStart(lock: HomeLock, phase: AtomicInteger, events: MutableList<String>): Int =
        lock.use { phase.get().also { events += "core start" } }

    @Test
    fun startDuringImportWaitsForItsSettings() = runBlocking(Dispatchers.Default) {
        val lock = HomeLock()
        val phase = AtomicInteger(OLD)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val competing = CompletableDeferred<Int>()
        val steps = Steps(phase, events, pause = { 100 }, onInstall = {
            launch { competing.complete(serviceStart(lock, phase, events)) }
        })
        assertNull(replaceSetup(lock, steps))
        assertEquals(DONE, withTimeout(5_000) { competing.await() })
        assertEquals(listOf("stop", "install", "settings", "remove"), events.take(4))
        assertTrue(events.indexOf("core start") > events.indexOf("remove"))
        assertTrue("start requested" in events)
    }

    @Test
    fun importWaitsForAStartInProgress() = runBlocking(Dispatchers.Default) {
        val lock = HomeLock()
        val phase = AtomicInteger(OLD)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val starting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val start = launch { lock.use { starting.complete(Unit); release.await(); events += "core start" } }
        starting.await()
        val import = async { replaceSetup(lock, Steps(phase, events)) }
        delay(200)
        assertEquals(OLD, phase.get()) // the import didn't stop the core under a starting service
        release.complete(Unit)
        start.join()
        assertNull(import.await())
        assertEquals(listOf("core start", "stop", "install", "settings", "remove", "start requested"), events.toList())
    }

    @Test
    fun failedInstallStartsTheOldSetupAgain() = runBlocking(Dispatchers.Default) {
        val lock = HomeLock()
        val phase = AtomicInteger(OLD)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val problem = replaceSetup(lock, Steps(phase, events, failInstall = "not a Syncthing backup: config.xml is missing"))
        assertEquals("Not a Syncthing backup: config.xml is missing", problem)
        assertEquals(listOf("stop", "start requested"), events.toList())
        withTimeout(1_000) { lock.use { } } // released
    }

    @Test
    fun undeletedBackupIsReported() = runBlocking(Dispatchers.Default) {
        val problem = replaceSetup(HomeLock(), Steps(AtomicInteger(OLD), Collections.synchronizedList(mutableListOf()), removed = false))
        assertTrue(problem!!.contains("could not be deleted"))
    }

    /** Starts at random moments around imports with random step lengths only ever see a whole setup. */
    @Test
    fun startsNeverSeeAHalfReplacedSetupProperty() = runBlocking(Dispatchers.Default) {
        val r = Random(26)
        repeat(150) { round ->
            val lock = HomeLock()
            val phase = AtomicInteger(OLD)
            val events = Collections.synchronizedList(mutableListOf<String>())
            val seen = Collections.synchronizedList(mutableListOf<Int>())
            val pauses = List(4) { r.nextLong(0, 4) }.iterator()
            val starts = List(r.nextInt(1, 4)) {
                val wait = r.nextLong(0, 12)
                launch { delay(wait); seen += serviceStart(lock, phase, events) }
            }
            replaceSetup(lock, Steps(phase, events, pause = { synchronized(pauses) { if (pauses.hasNext()) pauses.next() else 0 } }))
            starts.forEach { it.join() }
            seen.forEach { assertTrue("round $round saw phase $it", it == OLD || it == DONE) }
            assertFalse(events.isEmpty())
        }
    }
}
