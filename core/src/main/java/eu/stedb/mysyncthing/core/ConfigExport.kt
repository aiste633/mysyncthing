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
 * How an export is protected (go/bridge export.go). [bridge] is the bridge's
 * name for it; [extension] and [mimeType] are the saved file's.
 */
enum class ExportFormat(val bridge: String, val extension: String, val mimeType: String) {
    /** Argon2id and XChaCha20-Poly1305 over the whole zip: only this app opens it. */
    Strong(Stbridge.ExportStrong, "mysyncthing", "application/octet-stream"),

    /** WinZip AES-256 per entry: Syncthing-Fork and zip apps open it, but it is weak against guessing. */
    Compatible(Stbridge.ExportCompatible, "zip", "application/zip"),

    /** A plain zip. */
    None(Stbridge.ExportPlain, "zip", "application/zip"),
    ;

    val encrypted get() = this != None
}

/**
 * Exports this phone's setup as a backup that the import here reads: config,
 * device certificate and key, run conditions and proxy. Never the database,
 * the GUI login or the API key. See [ExportFormat] for the encryption.
 */
object ConfigExport {
    /** Every field written, so the bridge's strict reader finds no gaps. */
    private val json = Json { encodeDefaults = true }

    fun fileName(format: ExportFormat, now: LocalDateTime = LocalDateTime.now()): String =
        "mysyncthing-backup-" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + "." + format.extension

    internal fun settingsJson(c: RunConditions, p: ProxySettings): String = json.encodeToString(OwnSettings(conditions = c, proxy = p))

    /**
     * Writes the export to [uri] (from the system's save dialog) in [format]; [password] counts only
     * for an encrypted one. Returns null on success, else what went wrong.
     */
    suspend fun export(context: Context, uri: Uri, format: ExportFormat, password: String): UiText? = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        val home = File(context.filesDir, "syncthing")
        // Locked: an import replacing the setup meanwhile would mix its files with the old settings.
        SyncthingCore.homeLock.use {
            try {
                val js = settingsJson(settings.runConditions.first(), settings.proxy.first())
                // "wt": a provider that keeps the old bytes on "w" would leave a longer file's tail.
                (context.contentResolver.openFileDescriptor(uri, "wt") ?: throw UiTextException(text(R.string.export_cannot_write))).use { fd ->
                    Stbridge.exportBackup(fd.fd.toLong(), home.absolutePath, format.bridge, if (format.encrypted) password else "", js)
                }
                null
            } catch (e: Exception) {
                e.sentenceText()
            }
        }
    }
}
