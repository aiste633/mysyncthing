package eu.stedb.mysyncthing.e2e

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The test peer's REST API: a Syncthing built from our patched core, run on the
 * host by scripts/e2e/run.py and reached through `adb reverse`, so it is on the
 * emulator's loopback. The peer dials the phone at [phoneAddress], an
 * `adb forward` to the phone's sync port.
 */
class Peer(val id: String, private val key: String, private val phoneAddress: String) {
    private fun call(method: String, path: String, body: Any? = null): String {
        val c = URL("http://127.0.0.1:$PORT/$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5_000
            c.readTimeout = 10_000
            c.setRequestProperty("X-API-Key", key)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code < 300) { "peer $method /$path: HTTP $code $text" }
            return text
        } finally {
            c.disconnect()
        }
    }

    /** Adds the phone, reachable only at [phoneAddress]. */
    fun addPhone(phoneId: String) {
        call("POST", "rest/config/devices", JSONObject().put("deviceID", phoneId).put("name", "e2e-phone").put("addresses", JSONArray().put(phoneAddress)))
    }

    /** Shares the folder with the phone; the phone sees it as an offer. */
    fun share(folder: String, phoneId: String) {
        val f = JSONObject(call("GET", "rest/config/folders/$folder"))
        f.getJSONArray("devices").put(JSONObject().put("deviceID", phoneId))
        call("PUT", "rest/config/folders/$folder", f)
    }

    fun connected(device: String): Boolean =
        JSONObject(call("GET", "rest/system/connections")).getJSONObject("connections").optJSONObject(device)?.optBoolean("connected") == true

    /** The peer's own copy of [file]: null when it has none or it is deleted. */
    fun localFile(folder: String, file: String): JSONObject? = runCatching {
        JSONObject(call("GET", "rest/db/file?folder=$folder&file=$file")).optJSONObject("local")
    }.getOrNull()?.takeIf { !it.optBoolean("deleted") }

    /** How far [device] is with [folder], in percent, as the peer sees it. */
    fun completion(folder: String, device: String): Double =
        JSONObject(call("GET", "rest/db/completion?folder=$folder&device=$device")).getDouble("completion")

    companion object {
        const val PORT = 8385
    }
}
