package eu.stedb.mysyncthing.core

import android.content.Context
import android.net.Uri
import eu.stedb.mysyncthing.stbridge.Stbridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The app's settings in an export (go/bridge export.go ownSettings). */
@Serializable
data class OwnSettings(val version: Int = VERSION, val conditions: RunConditions, val proxy: ProxySettings) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * Exports this phone's setup as a zip that the import here reads: config,
 * device certificate and key, run conditions and proxy. Never the database,
 * the GUI login or the API key. With a password, every entry is WinZip AES-256.
 */
object ConfigExport {
    /** Every field written, so the bridge's strict reader finds no gaps. */
    private val json = Json { encodeDefaults = true }

    fun fileName(now: LocalDateTime = LocalDateTime.now()): String =
        "mysyncthing-backup-" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".zip"

    internal fun settingsJson(c: RunConditions, p: ProxySettings): String = json.encodeToString(OwnSettings(conditions = c, proxy = p))

    /** Writes the export to [uri] (from the system's save dialog). Returns null on success, else what went wrong. */
    suspend fun export(context: Context, uri: Uri, password: String): UiText? = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        val home = File(context.filesDir, "syncthing")
        // Locked: an import replacing the setup meanwhile would mix its files with the old settings.
        SyncthingCore.homeLock.use {
            try {
                val js = settingsJson(settings.runConditions.first(), settings.proxy.first())
                // "wt": a provider that keeps the old bytes on "w" would leave a longer file's tail.
                (context.contentResolver.openFileDescriptor(uri, "wt") ?: throw UiTextException(text(R.string.export_cannot_write))).use { fd ->
                    Stbridge.exportBackup(fd.fd.toLong(), home.absolutePath, password, js)
                }
                null
            } catch (e: Exception) {
                e.sentenceText()
            }
        }
    }
}
