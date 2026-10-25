package eu.stedb.mysyncthing.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    suspend fun device(id: String): Device = get("rest/config/devices/$id")
    suspend fun folders(): List<Folder> = get("rest/config/folders")
    suspend fun connections(): Connections = get("rest/system/connections")
    suspend fun folderStatus(folder: String): FolderStatus = get("rest/db/status", "folder" to folder)
    suspend fun completion(device: String): Completion = get("rest/db/completion", "device" to device)
    suspend fun pendingDevices(): Map<String, PendingDevice> = get("rest/cluster/pending/devices")
    suspend fun pendingFolders(): Map<String, PendingFolder> = get("rest/cluster/pending/folders")

    suspend fun events(since: Long, timeoutS: Int = 60): List<Event> =
        get("rest/events", "since" to since.toString(), "timeout" to timeoutS.toString())

    /** Unspecified fields take Syncthing's configured defaults. */
    suspend fun addDevice(id: String, name: String) = send("POST", "rest/config/devices", buildJsonObject {
        put("deviceID", id)
        put("name", name)
    })

    suspend fun removeDevice(id: String) = send("DELETE", "rest/config/devices/$id")

    suspend fun setDeviceName(id: String, name: String) =
        send("PATCH", "rest/config/devices/$id", buildJsonObject { put("name", name) })

    suspend fun addFolder(id: String, label: String, path: String, deviceIds: List<String>, type: String, versioning: Versioning) =
        send("POST", "rest/config/folders", buildJsonObject {
            put("id", id)
            put("label", label)
            put("path", path)
            put("type", type)
            putJsonArray("devices") { deviceIds.forEach { d -> addJsonObject { put("deviceID", d) } } }
            put("versioning", versioning.toJson())
        })

    /** Changes only the given fields of a folder's config. */
    suspend fun patchFolder(id: String, fields: JsonObject) = send("PATCH", "rest/config/folders/$id", fields)

    suspend fun setFolderDevices(id: String, deviceIds: List<String>) = patchFolder(id, buildJsonObject {
        putJsonArray("devices") { deviceIds.forEach { d -> addJsonObject { put("deviceID", d) } } }
    })

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
        setFolderDevices(folder.id, folder.devices.map { it.deviceID } + deviceId)
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

    suspend fun scan(folder: String? = null) =
        send("POST", "rest/db/scan", query = listOfNotNull(folder?.let { "folder" to it }))

    // Written out by hand: the default serializer drops type = "" (off), which PATCH then ignores.
    private fun Versioning.toJson() = buildJsonObject {
        put("type", type)
        putJsonObject("params") { params.forEach { (k, v) -> put(k, v) } }
    }

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String>): T {
        val body = call(request(path, query.toList()).get().build())
        return json.decodeFromString(body)
    }

    private suspend fun send(method: String, path: String, body: JsonObject? = null, query: List<Pair<String, String>> = emptyList()) {
        val rb: RequestBody? = body?.toString()?.toRequestBody(JSON)
            ?: if (method == "POST") ByteArray(0).toRequestBody(null) else null
        call(request(path, query).method(method, rb).build())
    }

    private fun request(path: String, query: List<Pair<String, String>>): Request.Builder {
        val url = base.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).header("X-API-Key", apiKey)
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, text.take(200))
            text
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
