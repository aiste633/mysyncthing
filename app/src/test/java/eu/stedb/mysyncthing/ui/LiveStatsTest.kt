package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.Connections
import eu.stedb.mysyncthing.core.ServiceStatus
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStatsTest {
    private val https = "https" + "://"
    // As Go reports a dial to an IPv6 address on a network without IPv6.
    private val noV6 = ServiceStatus("Post \"${https}discovery-v6.example.com/v2/?nolookup\": dial tcp [2001:db8::4a]:443: connect: network is unreachable")
    private val refused = ServiceStatus("Post \"${https}discovery.example.com/v2/\": dial tcp 192.0.2.10:443: connect: connection refused")

    private fun stats(vararg discovery: ServiceStatus) = LiveStats(
        null, emptyMap(), Connections(),
        SystemStatus(
            "ME",
            connectionServiceStatus = mapOf("tcp" + "://0.0.0.0:22000" to ServiceStatus()),
            discoveryEnabled = true,
            discoveryStatus = discovery.withIndex().associate { (i, st) -> "method $i" to st },
        ),
    )

    @Test
    fun noIpv6IsNotAFailure() {
        assertEquals("Not used: this network has no IPv6", unusedHere(noV6).en())
        val s = stats(ServiceStatus(), ServiceStatus(), ServiceStatus(), ServiceStatus(), noV6)
        assertEquals("Listening 1 of 1 · Discovery 4 of 4", s.healthText.en())
        assertEquals(SyncState.UpToDate, s.health)
    }

    @Test
    fun realFailuresStayAmber() {
        assertEquals(null, unusedHere(refused).en())
        val s = stats(ServiceStatus(), refused, noV6)
        assertEquals("Listening 1 of 1 · Discovery 1 of 2", s.healthText.en())
        assertEquals(SyncState.Paused, s.health)
    }
}
