package eu.stedb.mysyncthing.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import eu.stedb.mysyncthing.stbridge.Stbridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What a backup holds, as the import will apply it (go/bridge/forkimport.go):
 * a Syncthing-Fork backup ([source] "fork") or this app's export ("own").
 */
@Serializable
data class ForkPreview(
    val source: String = "fork",
    val deviceID: String,
    val deviceName: String = "",
    val configVersion: Int = 0,
    val folders: List<ForkFolder> = emptyList(),
    val devices: List<ForkDevice> = emptyList(),
    val conditions: RunConditions = RunConditions(),
    val proxy: ProxySettings = ProxySettings(),
    val notImported: List<ForkNotImported> = emptyList(),
)

@Serializable
data class ForkFolder(
    val id: String,
    val label: String = "",
    val path: String,
    val type: String = "",
    /** The directory is on this phone. */
    val exists: Boolean = false,
    /** And holds the folder marker. */
    val marker: Boolean = false,
)

@Serializable
data class ForkDevice(val id: String, val name: String = "")

/** A Fork setting without an equivalent here; [subject] names its folder or device. */
@Serializable
data class ForkNotImported(val key: String, val subject: String = "")

sealed interface ForkInspection {
    data class Ok(val preview: ForkPreview) : ForkInspection
    data object NeedPassword : ForkInspection
    data object WrongPassword : ForkInspection
    data class Failed(val message: UiText) : ForkInspection
}

/**
 * Imports a Syncthing-Fork backup (Fork: Settings > Import and Export) or this
 * app's own export (ConfigExport). The bridge reads it; this stops and
 * restarts the core around the change and stores the run conditions and proxy.
 */
object ForkImport {
    /** Where Fork writes its backup unless the user changed the path. */
    val defaultBackup: File get() = File(Environment.getExternalStorageDirectory(), "backups/syncthing/config.zip")

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun inspect(resolver: ContentResolver, uri: Uri, password: String): ForkInspection = withContext(Dispatchers.IO) {
        try {
            val preview = withFd(resolver, uri) { fd -> Stbridge.inspectForkBackup(fd.toLong(), password) }
            ForkInspection.Ok(json.decodeFromString<ForkPreview>(preview))
        } catch (e: Exception) {
            when (e.message) {
                Stbridge.errNeedPassword() -> ForkInspection.NeedPassword
                Stbridge.errWrongPassword() -> ForkInspection.WrongPassword
                else -> ForkInspection.Failed(e.sentenceText())
            }
        }
    }

    /**
     * Replaces this phone's Syncthing setup with the backup's. [keepIdentity]
     * takes over the backup's device ID. [deleteBackup] removes the file after
     * a successful import: it holds the private key in shared storage.
     * Returns null on success, else what went wrong. The core runs again
     * afterwards in either case. Not cancellable: once the core is stopped,
     * every step up to its restart must run, also when the caller goes away.
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        password: String,
        keepIdentity: Boolean,
        conditions: RunConditions,
        proxy: ProxySettings,
        deleteBackup: Boolean,
    ): UiText? = withContext(Dispatchers.IO + NonCancellable) {
        val settings = Settings(context)
        replaceSetup(SyncthingCore.homeLock, object : SetupReplacement {
            override suspend fun stopCore() {
                SyncthingService.stop(context)
                withTimeout(60_000) { SyncthingCore.state.first { it is CoreState.Stopped || it is CoreState.Failed } }
            }

            override fun install() {
                val home = File(context.filesDir, "syncthing")
                withFd(context.contentResolver, uri) { fd ->
                    Stbridge.importForkBackup(fd.toLong(), password, home.absolutePath, keepIdentity)
                }
            }

            override suspend fun applySettings() = listOf(
                settings.update { conditions.copy(pausedByUser = it.pausedByUser) },
                settings.setProxy(proxy),
                settings.setConditionPause(null), // the paused devices it remembers are the old setup's
                // Without this the service takes the config without a database for a move from another
                // phone and says so; the import screen already tells what the import means.
                settings.setFreshDatabaseExpected(true),
            ).all { it }

            override fun removeBackup() = !deleteBackup || delete(context.contentResolver, uri)

            override fun startCore() = SyncthingService.start(context)
        })
    }

    private inline fun <T> withFd(resolver: ContentResolver, uri: Uri, block: (Int) -> T): T =
        (resolver.openFileDescriptor(uri, "r") ?: throw UiTextException(text(R.string.import_cannot_open))).use { block(it.fd) }

    private fun delete(resolver: ContentResolver, uri: Uri): Boolean = runCatching {
        when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> File(uri.path!!).delete()
            else -> DocumentsContract.deleteDocument(resolver, uri)
        }
    }.getOrDefault(false)
}

/** The steps of replacing the core's setup, apart so their order can be tested. */
internal interface SetupReplacement {
    suspend fun stopCore()
    fun install()
    /** False if a setting could not be stored (storage full). */
    suspend fun applySettings(): Boolean
    /** False if the backup should have been deleted and wasn't. */
    fun removeBackup(): Boolean
    fun startCore()
}

/**
 * Runs [r] under [lock], from stopping the core to the last settings write, so
 * no start (rotation, boot, the app opening) can come in between and run the
 * old setup or the new one with the old settings. The core is started again
 * after the lock is released, also when stopping or installing failed.
 */
internal suspend fun replaceSetup(lock: HomeLock, r: SetupReplacement): UiText? {
    val outcome = lock.use {
        try {
            r.stopCore()
            r.install()
        } catch (e: Exception) {
            return@use e.sentenceText()
        }
        val saved = r.applySettings()
        val removed = r.removeBackup()
        val notSaved = text(R.string.import_settings_not_saved)
        val notDeleted = text(R.string.import_backup_not_deleted)
        when {
            !saved && !removed -> text(R.string.two_sentences, notSaved, notDeleted)
            !saved -> notSaved
            !removed -> notDeleted
            else -> null
        }
    }
    r.startCore()
    return outcome
}

/** Go errors start lower-case; shown on their own, they are sentences. */
internal fun sentence(message: String) = message.replaceFirstChar { it.uppercaseChar() }

/** [messageText], as a sentence of its own. */
internal fun Throwable.sentenceText(): UiText = (this as? UiTextException)?.text ?: raw(sentence(message ?: toString()))
