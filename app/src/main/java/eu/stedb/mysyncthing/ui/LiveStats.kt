package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.Connection
import eu.stedb.mysyncthing.core.Connections
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.SystemStatus

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

    /** "Listening 3 of 3 · Discovery 4 of 5". */
    val healthText: String get() {
        val l = "Listening ${listeners.values.count { it.error == null }} of ${listeners.size}"
        if (!status.discoveryEnabled) return "$l · Discovery off"
        return "$l · Discovery ${discovery.values.count { it.error == null }} of ${discovery.size}"
    }

    /** Red when nothing listens, amber when a part fails: one failing discovery server is common. */
    val health: SyncState get() = when {
        listeners.isNotEmpty() && listeners.values.all { it.error != null } -> SyncState.Problem
        (listeners.values + discovery.values).any { it.error != null } -> SyncState.Paused
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

fun speedText(s: Speed) = "↓ ${rate(s.down)} · ↑ ${rate(s.up)}"
