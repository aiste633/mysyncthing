package eu.stedb.mysyncthing.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.system.measureTimeMillis

/**
 * A stop cancels the service's run, whose event long-poll waits up to 60 s for the core to answer.
 * The cancel must end the call at once (PC, API 36: Stop did nothing for 7 to 9 s on an idle phone).
 */
class RestClientCancelTest {
    @Test
    fun cancellingTheLongPollEndsTheCallAtOnce() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = RestClient("127.0.0.1:${server.localPort}", "k")
            val poll = launch(Dispatchers.Default) { runCatching { client.events(since = 0) } }
            // The request has arrived; this server never answers, as the core does not while it has no events.
            withContext(Dispatchers.IO) { server.accept() }.use { conn ->
                val took = measureTimeMillis { poll.cancelAndJoin() }
                assertTrue("the cancel took $took ms", took < 2_000)
                conn.soTimeout = 2_000
                val input = conn.getInputStream()
                val closed = withContext(Dispatchers.IO) { generateSequence { input.read() }.first { it == -1 } }
                assertEquals("the connection is closed", -1, closed)
            }
        }
    }

    /** A write cut off halfway may or may not apply; a cancelled one runs to its end. */
    @Test
    fun aCancelledWriteRunsToItsEnd() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = RestClient("127.0.0.1:${server.localPort}", "k")
            val write = launch(Dispatchers.Default) { runCatching { client.restart() } }
            withContext(Dispatchers.IO) { server.accept() }.use { conn ->
                write.cancel()
                delay(300)
                assertFalse("the write ended before its answer", write.isCompleted)
                withContext(Dispatchers.IO) {
                    val request = conn.getInputStream().bufferedReader()
                    while (request.readLine().isNotEmpty()) Unit
                    conn.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
                withTimeout(2_000) { write.join() }
            }
        }
    }
}
