package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Connection
import eu.stedb.mysyncthing.core.Connections
import eu.stedb.mysyncthing.core.ServiceStatus
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.SystemStatus
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.joined
import eu.stedb.mysyncthing.core.raw
import eu.stedb.mysyncthing.core.text

/** Bytes per second each way. */
data class Speed(val down: Long, val up: Long)

/** One poll of the core: speeds since the previous poll, and this phone's connectivity. */
data class LiveStats(
    val total: Speed?,
    val devices: Map<String, Speed>,
    val connections: Connections,
    val status: SystemStatus,
) {
    val listeners get() = status.connectionServiceStatus
    val discovery get() = if (status.discoveryEnabled) status.discoveryStatus else emptyMap()

    /** Discovery methods this network can use at all; see [unusedHere]. */
    private val usableDiscovery get() = discovery.values.filter { unusedHere(it) == null }

    /** "Listening 3 of 3 · Discovery 4 of 4"; methods this network can't use are not counted. */
    val healthText: UiText get() {
        val listening = listeners.values.count { it.error == null }
        if (!status.discoveryEnabled) return text(R.string.health_discovery_off, listening, listeners.size)
        return text(R.string.health, listening, listeners.size, usableDiscovery.count { it.error == null }, usableDiscovery.size)
    }

    /** Red when nothing listens, amber when a part fails that this network could use. */
    val health: SyncState get() = when {
        listeners.isNotEmpty() && listeners.values.all { it.error != null } -> SyncState.Problem
        (listeners.values + usableDiscovery).any { it.error != null } -> SyncState.Paused
        else -> SyncState.UpToDate
    }

    companion object {
        fun of(status: SystemStatus, conns: Connections, prev: Pair<Long, Connections>?, nowMs: Long): LiveStats {
            if (prev == null || nowMs <= prev.first) return LiveStats(null, emptyMap(), conns, status)
            val seconds = (nowMs - prev.first) / 1000.0
            fun speed(a: Connection, b: Connection) = Speed(
                ((b.inBytesTotal - a.inBytesTotal) / seconds).toLong().coerceAtLeast(0),
                ((b.outBytesTotal - a.outBytesTotal) / seconds).toLong().coerceAtLeast(0),
            )
            val devices = conns.connections.mapNotNull { (id, now) ->
                val before = prev.second.connections[id]
                if (now.connected && before?.connected == true) id to speed(before, now) else null
            }.toMap()
            return LiveStats(speed(prev.second.total, conns.total), devices, conns, status)
        }
    }
}

/**
 * Why a method can't work on this network at all, or null. The default
 * servers include an IPv6-only one, which on the many networks without IPv6
 * fails with ENETUNREACH ("network is unreachable"): no route for that
 * address family, not a fault of this phone or the server.
 */
fun unusedHere(st: ServiceStatus): UiText? {
    val e = st.error ?: return null
    if (!e.contains("network is unreachable")) return null
    return text(if (IPV6_LITERAL.containsMatchIn(e)) R.string.unused_no_ipv6 else R.string.unused_no_route)
}

/** An IPv6 address as Go writes it in dial errors: "[2001:db8::1]:443". */
private val IPV6_LITERAL = Regex("""\[[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*]""")

fun speedText(s: Speed): UiText = listOf(raw("↓ "), rateText(s.down), raw(" · ↑ "), rateText(s.up)).joined("")
