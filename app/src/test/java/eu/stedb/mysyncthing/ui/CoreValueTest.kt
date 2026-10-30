package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.Credentials
import eu.stedb.mysyncthing.core.RestClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What a screen shows from the core outlives the screen (a rotation keeps its dialog),
 * but never the core run that read it: no value of a stopped or replaced core shows.
 */
class CoreValueTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val core = MutableStateFlow<CoreState>(CoreState.Stopped)

    @After
    fun tearDown() = scope.cancel()

    private fun running(): RestClient = RestClient("127.0.0.1:9", "test").also {
        core.value = CoreState.Running(it, "127.0.0.1:9", "ID", Credentials("test", "test", "test"))
    }

    @Test
    fun keepsWhatItReadUntilTheCoreStops() = runBlocking {
        val first = running()
        val reads = mutableListOf<RestClient>()
        val v = CoreValue(scope, core) { reads += this; "options" }
        assertNull("nothing before the first read", v.value.value)

        assertEquals("options", v.load())
        assertEquals("options", v.value.value)
        assertEquals("read with the running core's client", listOf(first), reads)

        core.value = CoreState.Stopped
        assertNull("gone with the core that read it", v.value.value)
        assertNull("no read without a core", v.load())
        assertEquals(1, reads.size)
    }

    @Test
    fun aChangeShowsAtOnceAndAFailedReadShowsNothing() = runBlocking {
        running()
        var fail = false
        val v = CoreValue(scope, core) { if (fail) throw IOException("HTTP 500") else 1 }
        v.load()
        v.show(2)
        assertEquals(2, v.value.value)

        fail = true
        assertNull(v.load())
        assertNull("a failed read leaves no stale value", v.value.value)
    }

    @Test
    fun aReadOvertakenByARestartIsDropped() = runBlocking {
        running()
        val gate = CompletableDeferred<Unit>()
        var n = 0
        val v = CoreValue(scope, core) { n++.also { if (it == 0) gate.await() } }
        val slow = async(Dispatchers.Unconfined) { v.load() }

        core.value = CoreState.Starting
        running() // a new start: a new client
        v.load()
        assertEquals("the new core's value", 1, v.value.value)

        gate.complete(Unit)
        assertEquals(0, slow.await())
        assertEquals("the old core's late answer is not shown", 1, v.value.value)
    }

    @Test
    fun aCancelledReadKeepsWhatIsShown() = runBlocking {
        running()
        val gate = CompletableDeferred<Unit>()
        var n = 0
        val v = CoreValue(scope, core) { n++.also { if (it == 1) gate.await() } }
        v.load()
        // A recreated screen cancels the read its old composition started.
        val read = async(Dispatchers.Unconfined) { v.load() }
        read.cancelAndJoin()
        assertTrue(read.isCancelled)
        assertEquals("still shown for the recreated screen's first frame", 0, v.value.value)
    }
}
