package eu.stedb.mysyncthing.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI

/**
 * On a Wi-Fi network with a manual or PAC proxy the system may send loopback calls to that
 * proxy too. The REST calls carry the API key and must reach the core on this phone, whatever
 * the network says.
 */
class RestClientProxyTest {
    @Test
    fun callsGoToTheCoreNotToTheSystemProxy() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        val system = ProxySelector.getDefault()
        ServerSocket(0, 1, loopback).use { proxy ->
            ServerSocket(0, 1, loopback).use { core ->
                // A system that sends everything, loopback included, to its proxy.
                ProxySelector.setDefault(object : ProxySelector() {
                    override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback, proxy.localPort)))
                    override fun connectFailed(uri: URI, address: SocketAddress, e: IOException) = Unit
                })
                try {
                    val client = RestClient.plain("127.0.0.1:${core.localPort}", "the-key")
                    val events = async(Dispatchers.IO) { client.events(since = 0) }
                    core.soTimeout = 5_000 // through the proxy, nothing arrives here
                    withContext(Dispatchers.IO) { core.accept() }.use { conn ->
                        val reader = conn.getInputStream().bufferedReader()
                        val head = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        assertTrue(head.first(), head.first().startsWith("GET /rest/events?"))
                        assertTrue("the API key went with it", head.any { it.equals("X-API-Key: the-key", ignoreCase = true) })
                        conn.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".toByteArray())
                            flush()
                        }
                    }
                    assertEquals(emptyList<Event>(), events.await())
                } finally {
                    ProxySelector.setDefault(system)
                }
            }
        }
    }
}
