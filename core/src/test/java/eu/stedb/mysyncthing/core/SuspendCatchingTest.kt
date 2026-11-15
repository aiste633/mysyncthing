package eu.stedb.mysyncthing.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class SuspendCatchingTest {
    @Test
    fun resultAndFailureAsRunCatching() = runBlocking {
        assertEquals(Result.success(7), runSuspendCatching { delay(1); 7 })
        val failure = runSuspendCatching { delay(1); throw IOException("no") }
        assertTrue(failure.exceptionOrNull() is IOException)
    }

    /** With runCatching the coroutine went on after the call: a retry, a "failed" message, a default. */
    @Test
    fun aCancelledCoroutineStopsThere() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var wentOn = false
        val job = launch {
            runSuspendCatching { started.complete(Unit); awaitCancellation() }
            wentOn = true
        }
        started.await()
        job.cancelAndJoin()
        assertFalse("the cancelled coroutine went on after the call", wentOn)
    }

    /** Cancelled, the block may still throw something else on its way out (a closed connection). */
    @Test
    fun aFailureWhileCancelledIsStillACancellation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var wentOn = false
        val job = launch {
            runSuspendCatching {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } catch (e: CancellationException) {
                    throw IOException("closed")
                }
            }
            // The next suspension point ends it.
            delay(1)
            wentOn = true
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(wentOn)
    }

    @Test
    fun aTimeoutInsideIsAFailure() = runBlocking {
        val result = runSuspendCatching { withTimeout(10) { awaitCancellation() } }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test
    fun aCancelledJobItWaitedForIsAFailure() = runBlocking {
        val other = CompletableDeferred<Int>().apply { cancel() }
        val result = runSuspendCatching { other.await() }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    /**
     * Calls of the app's own suspending API inside a plain runCatching. Not complete (it knows
     * only these names), but it holds the places that were changed.
     */
    @Test
    fun noRunCatchingAroundTheCoresCalls() {
        val suspending = Regex("""\b(client\??|c|core)\.[a-zA-Z]+\(|\bwithContext\(|\bdelay\(|\bload\(client\)|\bsetIgnores\(|\bdefaultIgnores\(\)|\bsettings\.conditionHolds\(\)|\bSyncStatus\.clearErrors\(""")
        val sources = listOf(File("src/main"), File("../app/src/main")).flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue("no sources found", sources.size > 20)
        val found = sources.flatMap { f ->
            val lines = f.readLines()
            lines.withIndex().filter { (i, line) ->
                // The call on the same line, or in the block that opens there.
                val at = line.indexOf("runCatching {")
                at >= 0 && !line.substring(0, at).endsWith("resumeWith(") && run {
                    val rest = line.substring(at)
                    val block = if (rest.trimEnd().endsWith("{")) lines.drop(i + 1).takeWhile { !it.trimStart().startsWith("}") }.joinToString("\n") else rest
                    suspending.containsMatchIn(block.replace(Regex("""\bc\.json\.[a-zA-Z<>]+\("""), "").replace(Regex("""\bclient\.json\.[a-zA-Z<>]+\("""), ""))
                }
            }.map { (i, line) -> "${f.path}:${i + 1}: ${line.trim()}" }
        }
        assertEquals("runCatching around suspending calls (use runSuspendCatching)", emptyList<String>(), found)
    }
}
