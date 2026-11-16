package eu.stedb.mysyncthing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's own HTTP goes only to the core, over TLS with its certificate pinned (core/CoreCertificate.kt).
 * Release builds allow no cleartext at all; debug builds allow it to loopback only, for the end-to-end
 * test's peer (androidTest e2e/Peer.kt), which runs in the app's process.
 */
class NetworkSecurityConfigTest {
    /** Each element that sets cleartextTrafficPermitted, with its value and its domains. */
    private fun cleartext(variant: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/$variant/res/xml/network_security_config.xml"))
        val all = doc.getElementsByTagName("*")
        return (0 until all.length).map { all.item(it) as org.w3c.dom.Element }
            .filter { it.hasAttribute("cleartextTrafficPermitted") }
            .map { e ->
                val domains = e.getElementsByTagName("domain")
                val names = (0 until domains.length).map { domains.item(it).textContent.trim() }
                "${e.tagName}=${e.getAttribute("cleartextTrafficPermitted")}" + names.joinToString("") { " $it" }
            }
    }

    @Test
    fun releaseAllowsNoCleartext() = assertEquals(listOf("base-config=false"), cleartext("main"))

    @Test
    fun debugAllowsCleartextToLoopbackOnly() =
        assertEquals(listOf("base-config=false", "domain-config=true " + "127.0.0.1"), cleartext("debug"))
}
