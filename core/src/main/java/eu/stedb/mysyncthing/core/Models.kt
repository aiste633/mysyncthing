package eu.stedb.mysyncthing.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Only the fields the native UI uses; the REST API returns many more.

@Serializable
data class SystemStatus(
    val myID: String,
    val uptime: Long = 0,
    val urVersionMax: Int = 0,
    val connectionServiceStatus: Map<String, ServiceStatus> = emptyMap(),
    val discoveryEnabled: Boolean = false,
    val discoveryStatus: Map<String, ServiceStatus> = emptyMap(),
)

/** One listener or discovery method; [error] null means it works. */
@Serializable
data class ServiceStatus(val error: String? = null)

/** A notice for the user, as the web GUI shows them; kept by the core until cleared. */
@Serializable
data class SystemError(@SerialName("when") val time: String = "", val message: String = "") {
    /** The message without the log's key=value tail, for people. */
    val text: String get() = readableNotice(message)
}

/**
 * Core notices are slog lines such as `Failed to start (error="port in use"
 * log.pkg=api)`. Keeps the message and the values, drops the log.* keys:
 * "Failed to start: port in use". Values that are slog lines themselves,
 * as in an external error report, are made readable the same way.
 */
fun readableNotice(message: String): String {
    if (!message.endsWith(")")) return message
    // The first " (" whose rest is all key=value pairs; a quoted value may itself hold brackets.
    var open = message.indexOf(" (")
    while (open >= 0) {
        val tail = message.substring(open + 2, message.length - 1)
        val pairs = NOTICE_PAIR.findAll(tail).toList()
        if (pairs.isNotEmpty() && pairs.joinToString(" ") { it.value } == tail.trim()) {
            val values = pairs.filterNot { it.groupValues[1].startsWith("log.") }.map { m ->
                // A wrapped report carries another slog line as its value.
                readableNotice(m.groupValues[2].let { v -> if (v.startsWith("\"")) v.substring(1, v.length - 1).replace("\\\"", "\"") else v })
            }
            val head = message.substring(0, open)
            return if (values.isEmpty()) head else "$head: ${values.joinToString(", ")}"
        }
        open = message.indexOf(" (", open + 1)
    }
    return message
}

private val NOTICE_PAIR = Regex("""([\w.]+)=("(?:[^"\\]|\\.)*"|\S+)""")

@Serializable
data class SystemErrors(val errors: List<SystemError>? = null)

/** Usage-reporting consent, as upstream stores it: urAccepted 0 = not asked, -1 = declined, >0 = accepted version. */
@Serializable
data class Options(
    val urAccepted: Int = 0,
    val urSeen: Int = 0,
    /** KiB/s, 0 for no limit. */
    val maxRecvKbps: Int = 0,
    val maxSendKbps: Int = 0,
    val limitBandwidthInLan: Boolean = false,
    val localAnnounceEnabled: Boolean = true,
    val globalAnnounceEnabled: Boolean = true,
    val relaysEnabled: Boolean = true,
    val natEnabled: Boolean = true,
    val listenAddresses: List<String> = listOf("default"),
    val globalAnnounceServers: List<String> = listOf("default"),
)

@Serializable
data class DefaultIgnores(val lines: List<String> = emptyList())

@Serializable
data class RestartRequired(val requiresRestart: Boolean = false)

@Serializable
data class Device(
    val deviceID: String = "",
    val name: String = "",
    val paused: Boolean = false,
    val addresses: List<String> = listOf("dynamic"),
    /** "metadata", "always" or "never". */
    val compression: String = "metadata",
    val introducer: Boolean = false,
    val autoAcceptFolders: Boolean = false,
    /** KiB/s, 0 for no limit. */
    val maxSendKbps: Int = 0,
    val maxRecvKbps: Int = 0,
    /** 0 lets Syncthing choose. */
    val numConnections: Int = 0,
    val group: String = "",
    val ignoredFolders: List<ObservedFolder> = emptyList(),
    /** Only gets folders encrypted; the core drops introducer and auto-accept for it. */
    val untrusted: Boolean = false,
)

/** A folder offer the user ignored, stored on the device that offered it. */
@Serializable
data class ObservedFolder(val time: String = "", val id: String = "", val label: String = "")

/** A device whose connection request the user ignored. */
@Serializable
data class ObservedDevice(val time: String = "", val deviceID: String = "", val name: String = "", val address: String = "")

/** The parts of the full config that hold ignored requests; upstream has no narrower endpoint for them. */
@Serializable
data class IgnoredRequests(
    val remoteIgnoredDevices: List<ObservedDevice> = emptyList(),
    val devices: List<Device> = emptyList(),
)

/** One entry of the discovery cache: where a device was last announced. */
@Serializable
data class Discovered(val addresses: List<String> = emptyList())

@Serializable
data class FolderDevice(
    val deviceID: String,
    val introducedBy: String = "",
    /** Set for an untrusted device: it gets the folder encrypted with this password. */
    val encryptionPassword: String = "",
)

/** Upstream versioning config; `type` "" means off. Param values are strings, as upstream stores them. */
@Serializable
data class Versioning(val type: String = "", val params: Map<String, String> = emptyMap())

@Serializable
data class Folder(
    val id: String = "",
    val label: String = "",
    val path: String = "",
    val type: String = "sendreceive",
    val paused: Boolean = false,
    val devices: List<FolderDevice> = emptyList(),
    val versioning: Versioning = Versioning(),
    val fsWatcherEnabled: Boolean = true,
    val rescanIntervalS: Int = 3600,
    val order: String = "random",
    val minDiskFree: DiskSize = DiskSize(),
    val group: String = "",
    val ignorePerms: Boolean = false,
) {
    /** Changes the user must decide on: edits on a receive-only phone, or remote edits a send-only phone ignores. */
    fun needsReview(st: FolderStatus?): Boolean = st != null && when (type) {
        "receiveonly" -> st.receiveOnlyTotalItems > 0
        "sendonly" -> st.needTotalItems > 0
        else -> false
    }
}

/** Upstream's size setting: [unit] is "%" or an SI unit (kB, MB, GB, TB). */
@Serializable
data class DiskSize(val value: Double = 1.0, val unit: String = "%")

@Serializable
data class FileError(val path: String = "", val error: String = "")

@Serializable
data class FolderErrors(val errors: List<FileError>? = null)

@Serializable
data class Ignores(val ignore: List<String>? = null)

@Serializable
data class FolderStatus(
    val state: String = "",
    val globalBytes: Long = 0,
    val localBytes: Long = 0,
    val needBytes: Long = 0,
    val errors: Int = 0,
    val error: String = "",
    val needTotalItems: Int = 0,
    val receiveOnlyTotalItems: Int = 0,
    val receiveOnlyChangedBytes: Long = 0,
    val globalFiles: Int = 0,
    val globalDirectories: Int = 0,
    val localFiles: Int = 0,
    val localDirectories: Int = 0,
)

@Serializable
data class LastFile(val at: String = "", val filename: String = "", val deleted: Boolean = false)

@Serializable
data class FolderStats(val lastScan: String = "", val lastFile: LastFile = LastFile())

@Serializable
data class DeviceStats(val lastSeen: String = "")

/** A file in a need list; the REST API sends many more fields. */
@Serializable
data class FileEntry(val name: String = "", val size: Long = 0, val deleted: Boolean = false, val type: String = "") {
    val isDirectory get() = type.endsWith("DIRECTORY")
}

/** What this device still needs: being pulled now, queued, then the rest. */
@Serializable
data class NeedPage(
    val progress: List<FileEntry>? = null,
    val queued: List<FileEntry>? = null,
    val rest: List<FileEntry>? = null,
)

@Serializable
data class RemoteNeedPage(val files: List<FileEntry>? = null)

/** One file being downloaded, from the DownloadProgress event. */
@Serializable
data class FileProgress(val bytesDone: Long = 0, val bytesTotal: Long = 0)

@Serializable
data class Connection(
    val connected: Boolean = false,
    val paused: Boolean = false,
    val address: String = "",
    val type: String = "",
    val isLocal: Boolean = false,
    val inBytesTotal: Long = 0,
    val outBytesTotal: Long = 0,
    val clientVersion: String = "",
)

/** [total] is all traffic of this device, the sum over every connection. */
@Serializable
data class Connections(val connections: Map<String, Connection> = emptyMap(), val total: Connection = Connection())

@Serializable
data class Completion(val completion: Double = 0.0, val needBytes: Long = 0, val needItems: Int = 0)

@Serializable
data class PendingDevice(val name: String = "", val address: String = "", val time: String = "")

@Serializable
data class PendingFolderOffer(val label: String = "", val time: String = "")

@Serializable
data class PendingFolder(val offeredBy: Map<String, PendingFolderOffer> = emptyMap())

@Serializable
data class Event(
    val id: Long,
    val type: String,
    val time: String = "",
    val data: JsonElement? = null,
)

/** Event types after which the UI should refresh its snapshot. */
val REFRESH_EVENTS = setOf(
    "ConfigSaved", "StateChanged", "FolderSummary", "FolderCompletion", "FolderErrors",
    "DeviceConnected", "DeviceDisconnected", "DevicePaused", "DeviceResumed",
    "FolderPaused", "FolderResumed", "PendingDevicesChanged", "PendingFoldersChanged",
)

@Serializable
data class ApiError(@SerialName("error") val message: String = "")
