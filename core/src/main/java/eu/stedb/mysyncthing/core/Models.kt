package eu.stedb.mysyncthing.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Only the fields the native UI uses; the REST API returns many more.

@Serializable
data class SystemStatus(val myID: String, val uptime: Long = 0)

@Serializable
data class Device(
    val deviceID: String,
    val name: String = "",
    val paused: Boolean = false,
    val addresses: List<String> = listOf("dynamic"),
)

@Serializable
data class FolderDevice(val deviceID: String)

/** Upstream versioning config; `type` "" means off. Param values are strings, as upstream stores them. */
@Serializable
data class Versioning(val type: String = "", val params: Map<String, String> = emptyMap())

@Serializable
data class Folder(
    val id: String,
    val label: String = "",
    val path: String,
    val type: String = "sendreceive",
    val paused: Boolean = false,
    val devices: List<FolderDevice> = emptyList(),
    val versioning: Versioning = Versioning(),
    val fsWatcherEnabled: Boolean = true,
    val rescanIntervalS: Int = 3600,
)

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
)

@Serializable
data class Connection(
    val connected: Boolean = false,
    val paused: Boolean = false,
    val address: String = "",
    val type: String = "",
    val isLocal: Boolean = false,
    val inBytesTotal: Long = 0,
    val outBytesTotal: Long = 0,
)

@Serializable
data class Connections(val connections: Map<String, Connection> = emptyMap())

@Serializable
data class Completion(val completion: Double = 0.0, val needBytes: Long = 0)

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
