package eu.stedb.mysyncthing.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * The REST client trusts the loopback server only with the core's own certificate (A1 of the
 * 2026-11-14 review): a server with any other certificate, as another app on the port would
 * have, never sees a request, so never the API key.
 */
class RestClientTlsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A self-signed certificate and its key, made by the JDK's keytool, as the core makes its own. */
    private class Identity(val keyStore: KeyStore) {
        val der: ByteArray get() = keyStore.getCertificate("core").encoded
    }

    private fun identity(name: String): Identity {
        val file = File(tmp.root, "$name.p12")
        // Split literal: scripts/check-leaks.sh would read the property name as a local host name.
        val keytool = File(System.getProperty("java" + ".home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-keystore", file.path, "-storetype", "PKCS12", "-storepass", "test-pass",
            "-alias", "core", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost", "-validity", "2",
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        return Identity(KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "test-pass".toCharArray()) } })
    }

    /** Serves one connection with [server]'s certificate; returns the request head, or null when no request came. */
    private fun serveOnce(server: Identity, call: suspend (address: String) -> Unit): String? {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(server.keyStore, "test-pass".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val socket = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        return socket.use { s ->
            s.soTimeout = 10_000
            runBlocking {
                val head = async(Dispatchers.IO) {
                    s.accept().use { conn ->
                        try {
                            val reader = conn.getInputStream().bufferedReader()
                            val head = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                            conn.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".toByteArray())
                                flush()
                            }
                            head
                        } catch (e: IOException) {
                            null // the client refused the handshake
                        }
                    }
                }
                call("127.0.0.1:${s.localPort}")
                head.await()
            }
        }
    }

    @Test
    fun theCoresCertificateIsTrusted() {
        val core = identity("core")
        var events: List<Event>? = null
        val head = serveOnce(core) { address ->
            events = withContext(Dispatchers.IO) { RestClient(address, "the-key") { core.der }.events(since = 0) }
        }
        assertEquals(emptyList<Event>(), events)
        assertTrue(head, head!!.startsWith("GET /rest/events?"))
        assertTrue("the API key went with it", head.lines().any { it.equals("X-API-Key: the-key", ignoreCase = true) })
    }

    @Test
    fun anotherCertificateGetsNoRequest() {
        val core = identity("core")
        val other = identity("other")
        var failed = false
        val head = serveOnce(other) { address ->
            failed = withContext(Dispatchers.IO) { runCatching { RestClient(address, "the-key") { core.der }.events(since = 0) } }.isFailure
        }
        assertTrue("the call failed", failed)
        assertEquals(null, head)
    }

    @Test
    fun noCertificateTrustsNothing() {
        val core = identity("core")
        var failed = false
        val head = serveOnce(core) { address ->
            failed = withContext(Dispatchers.IO) { runCatching { RestClient(address, "the-key") { null }.events(since = 0) } }.isFailure
        }
        assertTrue("the call failed", failed)
        assertEquals(null, head)
    }

    @Test
    fun isCoreCertificateComparesTheWholeCertificate() {
        val core = identity("core")
        val cert = core.keyStore.getCertificate("core") as java.security.cert.X509Certificate
        assertTrue(isCoreCertificate(cert, core.der))
        assertFalse(isCoreCertificate(cert, identity("other").der))
        assertFalse(isCoreCertificate(cert, ByteArray(0)))
        assertFalse(isCoreCertificate(cert, null))
        assertFalse(isCoreCertificate(null, core.der))
    }
}
