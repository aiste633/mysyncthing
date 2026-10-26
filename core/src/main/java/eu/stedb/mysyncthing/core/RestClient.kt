package eu.stedb.mysyncthing.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException("HTTP $code: $message")

/** Thin client for Syncthing's REST API on loopback. */
class RestClient(address: String, private val apiKey: String) {
    private val base: HttpUrl = HttpUrl.Builder().scheme("http").host(address.substringBeforeLast(':'))
        .port(address.substringAfterLast(':').toInt()).build()

    private val http = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS) // event long-poll uses 60 s
        .build()

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun status(): SystemStatus = get("rest/system/status")
    suspend fun devices(): List<Device> = get("rest/config/devices")
    suspend fun options(): Options = get("rest/config/options")
    suspend fun patchOptions(fields: JsonObject) = send("PATCH", "rest/config/options", fields)
    suspend fun restartRequired(): Boolean = get<RestartRequired>("rest/config/restart-required").requiresRestart

    /** The bridge starts the core again in place, on the same address. */
    suspend fun restart() = send("POST", "rest/system/restart")
    suspend fun device(id: String): Device = get("rest/config/devices/$id")
    suspend fun folders(): List<Folder> = get("rest/config/folders")
    suspend fun connections(): Connections = get("rest/system/connections")
    suspend fun folderStatus(folder: String): FolderStatus = get("rest/db/status", "folder" to folder)
    suspend fun completion(device: String, folder: String? = null): Completion =
        get("rest/db/completion", *listOfNotNull("device" to device, folder?.let { "folder" to it }).toTypedArray())
    suspend fun folderStats(): Map<String, FolderStats> = get("rest/stats/folder")
    suspend fun deviceStats(): Map<String, DeviceStats> = get("rest/stats/device")
    suspend fun systemErrors(): List<SystemError> = get<SystemErrors>("rest/system/error").errors.orEmpty()
    suspend fun clearErrors() = send("POST", "rest/system/error/clear")

    suspend fun need(folder: String, page: Int, perPage: Int): NeedPage =
        get("rest/db/need", "folder" to folder, "page" to page.toString(), "perpage" to perPage.toString())

    suspend fun localChanged(folder: String, page: Int, perPage: Int): RemoteNeedPage =
        get("rest/db/localchanged", "folder" to folder, "page" to page.toString(), "perpage" to perPage.toString())

    /** Receive-only: drops this phone's changes and takes the other devices' version. */
    suspend fun revert(folder: String) = send("POST", "rest/db/revert", query = listOf("folder" to folder))

    /** Send-only: makes this phone's version win on every device. */
    suspend fun override(folder: String) = send("POST", "rest/db/override", query = listOf("folder" to folder))

    suspend fun remoteNeed(folder: String, device: String, page: Int, perPage: Int): RemoteNeedPage =
        get("rest/db/remoteneed", "folder" to folder, "device" to device, "page" to page.toString(), "perpage" to perPage.toString())
    suspend fun pendingDevices(): Map<String, PendingDevice> = get("rest/cluster/pending/devices")
    suspend fun pendingFolders(): Map<String, PendingFolder> = get("rest/cluster/pending/folders")

    /** The core's last [limit] file changes, kept in memory since it started; returns at once. */
    suspend fun diskEvents(limit: Int): List<Event> =
        get("rest/events/disk", "since" to "0", "limit" to limit.toString(), "timeout" to "0")

    /** Archived versions per file path. */
    suspend fun versions(folder: String): Map<String, List<FileVersion>> = get("rest/folder/versions", "folder" to folder)

    /** Restores each file to the version with the given time; returns errors by file. */
    suspend fun restoreVersions(folder: String, versions: Map<String, String>): Map<String, String> {
        val body = buildJsonObject { versions.forEach { (file, time) -> put(file, time) } }
        return json.decodeFromString(send("POST", "rest/folder/versions", body, listOf("folder" to folder)).ifBlank { "{}" })
    }

    suspend fun events(since: Long, timeoutS: Int = 60): List<Event> =
        get("rest/events", "since" to since.toString(), "timeout" to timeoutS.toString())

    /** Unspecified fields take Syncthing's configured defaults. */
    suspend fun addDevice(id: String, name: String) = send("POST", "rest/config/devices", buildJsonObject {
        put("deviceID", id)
        put("name", name)
    })

    suspend fun removeDevice(id: String) = send("DELETE", "rest/config/devices/$id")

    /** Settings new folders and devices start with; their IDs are empty. */
    suspend fun defaultFolder(): Folder = get("rest/config/defaults/folder")
    suspend fun defaultDevice(): Device = get("rest/config/defaults/device")
    suspend fun patchDefaultFolder(fields: JsonObject) = send("PATCH", "rest/config/defaults/folder", fields)
    suspend fun patchDefaultDevice(fields: JsonObject) = send("PATCH", "rest/config/defaults/device", fields)
    suspend fun defaultIgnores(): List<String> = get<DefaultIgnores>("rest/config/defaults/ignores").lines
    suspend fun setDefaultIgnores(lines: List<String>) = send("PUT", "rest/config/defaults/ignores", buildJsonObject {
        putJsonArray("lines") { lines.forEach { add(JsonPrimitive(it)) } }
    })

    /** Sets the new-folder defaults to [path] and ignorePerms while the path is still upstream's "~". */
    suspend fun adoptFolderDefaults(path: String) {
        val d = get<JsonObject>("rest/config/defaults/folder")
        val current = (d["path"] as? JsonPrimitive)?.content.orEmpty()
        if (current != "~" && current.isNotEmpty()) return
        send("PATCH", "rest/config/defaults/folder", buildJsonObject { put("path", path); put("ignorePerms", true) })
    }

    suspend fun patchDevice(id: String, fields: JsonObject) = send("PATCH", "rest/config/devices/$id", fields)

    /** Devices heard on the local network, plus the global lookups the core has cached. */
    suspend fun discovery(): Map<String, Discovered> = get("rest/system/discovery")

    suspend fun ignoredRequests(): IgnoredRequests = get("rest/config")

    /** Lets a device's connection requests through again; the request returns on its next attempt. */
    suspend fun unignoreDevice(id: String) {
        val cfg = get<JsonObject>("rest/config")
        val left = (cfg["remoteIgnoredDevices"] as? JsonArray).orEmpty()
            .filter { (it as? JsonObject)?.get("deviceID")?.let { v -> (v as? JsonPrimitive)?.content } != id }
        send("PUT", "rest/config", JsonObject(cfg + ("remoteIgnoredDevices" to JsonArray(left))))
    }

    /** Lets [device] offer [folder] again. */
    suspend fun unignoreFolder(folder: String, device: String) {
        val d = get<JsonObject>("rest/config/devices/$device")
        val left = (d["ignoredFolders"] as? JsonArray).orEmpty()
            .filter { (it as? JsonObject)?.get("id")?.let { v -> (v as? JsonPrimitive)?.content } != folder }
        patchDevice(device, buildJsonObject { put("ignoredFolders", JsonArray(left)) })
    }

    /**
     * Records the answer to the consent prompt exactly as upstream's web GUI
     * does: "No" keeps an earlier accepted version, so only a format change is declined.
     */
    suspend fun answerUsagePrompt(accept: Boolean) {
        val max = status().urVersionMax
        val current = options().urAccepted
        setUsageOptions(if (accept) max else if (current == 0) -1 else current, max)
    }

    /** The Settings switch: on accepts the current format, off declines entirely. */
    suspend fun setUsageReporting(on: Boolean) {
        val max = status().urVersionMax
        setUsageOptions(if (on) max else -1, max)
    }

    private suspend fun setUsageOptions(accepted: Int, seen: Int) = send("PATCH", "rest/config/options", buildJsonObject {
        put("urAccepted", accepted)
        put("urSeen", seen)
    })

    /** The report that would be sent, for the Preview button. */
    suspend fun usageReport(): String = call(request("rest/svc/report", emptyList()).get().build())

    suspend fun setDeviceName(id: String, name: String) =
        send("PATCH", "rest/config/devices/$id", buildJsonObject { put("name", name) })

    suspend fun addFolder(
        id: String, label: String, path: String, devices: List<FolderDevice>, type: String, versioning: Versioning, paused: Boolean = false,
    ) =
        send("POST", "rest/config/folders", buildJsonObject {
            put("id", id)
            put("label", label)
            put("path", path)
            put("type", type)
            put("paused", paused)
            // Android's shared storage has no real Unix permissions to sync.
            put("ignorePerms", true)
            putFolderDevices(devices)
            put("versioning", versioning.toJson())
        })

    /** Changes only the given fields of a folder's config. */
    suspend fun patchFolder(id: String, fields: JsonObject) = send("PATCH", "rest/config/folders/$id", fields)

    /** Whole entries, so other devices keep their introducer and encryption password. */
    suspend fun setFolderDevices(id: String, devices: List<FolderDevice>) = patchFolder(id, buildJsonObject {
        putFolderDevices(devices)
    })

    private fun JsonObjectBuilder.putFolderDevices(devices: List<FolderDevice>) = put("devices", folderDevicesJson(devices))

    suspend fun setVersioning(id: String, versioning: Versioning) = patchFolder(id, buildJsonObject {
        put("versioning", versioning.toJson())
    })

    suspend fun folderErrors(id: String): FolderErrors = get("rest/folder/errors", "folder" to id)
    suspend fun ignores(id: String): List<String> = get<Ignores>("rest/db/ignores", "folder" to id).ignore.orEmpty()

    suspend fun setIgnores(id: String, lines: List<String>) = send("POST", "rest/db/ignores", buildJsonObject {
        putJsonArray("ignore") { lines.forEach { add(JsonPrimitive(it)) } }
    }, query = listOf("folder" to id))

    suspend fun removeFolder(id: String) = send("DELETE", "rest/config/folders/$id")

    suspend fun setFolderPaused(id: String, paused: Boolean) =
        send("PATCH", "rest/config/folders/$id", buildJsonObject { put("paused", paused) })

    suspend fun shareFolder(folder: Folder, deviceId: String) {
        if (folder.devices.any { it.deviceID == deviceId }) return
        setFolderDevices(folder.id, folder.devices + FolderDevice(deviceId))
    }

    /**
     * Ignores a device for good, as the web GUI's Ignore does. Only deleting
     * the pending entry would bring it back on its next connection attempt.
     */
    suspend fun ignoreDevice(id: String) {
        val p = pendingDevices()[id] ?: return
        val cfg = get<JsonObject>("rest/config")
        val entry = buildJsonObject {
            put("deviceID", id); put("name", p.name); put("address", p.address); put("time", Instant.now().toString())
        }
        val ignored = (cfg["remoteIgnoredDevices"] as? JsonArray).orEmpty() + entry
        send("PUT", "rest/config", JsonObject(cfg + ("remoteIgnoredDevices" to JsonArray(ignored))))
    }

    /** Ignores one device's offer of a folder for good, stored on that device as upstream does. */
    suspend fun ignoreFolder(folder: String, device: String) {
        val label = pendingFolders()[folder]?.offeredBy?.get(device)?.label.orEmpty()
        val d = get<JsonObject>("rest/config/devices/$device")
        val entry = buildJsonObject { put("id", folder); put("label", label); put("time", Instant.now().toString()) }
        val ignored = (d["ignoredFolders"] as? JsonArray).orEmpty() + entry
        send("PATCH", "rest/config/devices/$device", buildJsonObject { put("ignoredFolders", JsonArray(ignored)) })
    }

    /** Pauses or resumes all devices; used to enforce run conditions without stopping the core. */
    suspend fun setAllPaused(paused: Boolean) = send("POST", if (paused) "rest/system/pause" else "rest/system/resume")

    suspend fun setDevicePaused(id: String, paused: Boolean) =
        send("POST", if (paused) "rest/system/pause" else "rest/system/resume", query = listOf("device" to id))

    /** Scans all folders, one folder, or only [subs] (paths in the folder) of one folder. */
    suspend fun scan(folder: String? = null, subs: List<String> = emptyList()) =
        send("POST", "rest/db/scan", query = listOfNotNull(folder?.let { "folder" to it }) + subs.map { "sub" to it })

    // Written out by hand: the default serializer drops type = "" (off), which PATCH then ignores.
    private fun Versioning.toJson() = buildJsonObject {
        put("type", type)
        putJsonObject("params") { params.forEach { (k, v) -> put(k, v) } }
    }

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String>): T {
        val body = call(request(path, query.toList()).get().build())
        return json.decodeFromString(body)
    }

    /** Returns the response body; most callers ignore it. */
    private suspend fun send(method: String, path: String, body: JsonObject? = null, query: List<Pair<String, String>> = emptyList()): String {
        val rb: RequestBody? = body?.toString()?.toRequestBody(JSON)
            ?: if (method == "POST") ByteArray(0).toRequestBody(null) else null
        return call(request(path, query).method(method, rb).build())
    }

    private fun request(path: String, query: List<Pair<String, String>>): Request.Builder {
        val url = base.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).header("X-API-Key", apiKey)
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw ApiException(resp.code, text.take(200))
            text
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/**
 * Every field of every entry, even when empty: the core decodes a PATCH onto
 * the stored list, reusing its entries by position, so a field left out keeps
 * whatever the entry at that position had, such as a removed device's password.
 */
internal fun folderDevicesJson(devices: List<FolderDevice>): JsonArray = buildJsonArray {
    devices.forEach { d ->
        addJsonObject {
            put("deviceID", d.deviceID)
            put("introducedBy", d.introducedBy)
            put("encryptionPassword", d.encryptionPassword)
        }
    }
}
