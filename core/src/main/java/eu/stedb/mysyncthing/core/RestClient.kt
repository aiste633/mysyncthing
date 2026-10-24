package eu.stedb.mysyncthing.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
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

    suspend fun addFolder(id: String, label: String, path: String, deviceIds: List<String>) =
        send("POST", "rest/config/folders", buildJsonObject {
            put("id", id)
            put("label", label)
            put("path", path)
            putJsonArray("devices") { deviceIds.forEach { d -> addJsonObject { put("deviceID", d) } } }
        })

    suspend fun removeFolder(id: String) = send("DELETE", "rest/config/folders/$id")

    suspend fun setFolderPaused(id: String, paused: Boolean) =
        send("PATCH", "rest/config/folders/$id", buildJsonObject { put("paused", paused) })

    suspend fun shareFolder(folder: Folder, deviceId: String) {
        if (folder.devices.any { it.deviceID == deviceId }) return
        send("PATCH", "rest/config/folders/${folder.id}", buildJsonObject {
            putJsonArray("devices") {
                (folder.devices.map { it.deviceID } + deviceId).forEach { d -> addJsonObject { put("deviceID", d) } }
            }
        })
    }

    suspend fun dismissPendingDevice(id: String) = send("DELETE", "rest/cluster/pending/devices", query = listOf("device" to id))

    /** Pauses or resumes all devices; used to enforce run conditions without stopping the core. */
    suspend fun setAllPaused(paused: Boolean) = send("POST", if (paused) "rest/system/pause" else "rest/system/resume")

    suspend fun setDevicePaused(id: String, paused: Boolean) =
        send("POST", if (paused) "rest/system/pause" else "rest/system/resume", query = listOf("device" to id))

    suspend fun scan(folder: String? = null) =
        send("POST", "rest/db/scan", query = listOfNotNull(folder?.let { "folder" to it }))

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
