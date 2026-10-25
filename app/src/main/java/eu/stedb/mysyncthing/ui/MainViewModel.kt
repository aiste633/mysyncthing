package eu.stedb.mysyncthing.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.FileError
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.RestClient
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.Settings
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Summary
import eu.stedb.mysyncthing.core.SyncStatus
import eu.stedb.mysyncthing.core.SyncthingCore
import eu.stedb.mysyncthing.core.Versioning
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    val core: StateFlow<CoreState> = SyncthingCore.state
    val runConditions: StateFlow<RunConditions> =
        settings.runConditions.stateIn(viewModelScope, SharingStarted.Eagerly, RunConditions())

    /** Followed by the service, so the screens add no extra event long-poll. */
    val snapshot: StateFlow<Snapshot> = SyncStatus.snapshot
    val summary: StateFlow<Summary> = SyncStatus.summary

    private val _message = Channel<String>(Channel.BUFFERED)
    val messages = _message.receiveAsFlow()

    private val client: RestClient? get() = (core.value as? CoreState.Running)?.client

    private fun act(label: String, done: String? = null, block: suspend RestClient.() -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.block() }
                .onSuccess { done?.let { _message.send(it) } }
                .onFailure { _message.send("$label failed: ${it.message}") }
        }
    }

    /** One-off reads for detail screens; null when the core is not running or the call fails. */
    suspend fun ignores(folder: String): List<String>? = runCatching { client?.ignores(folder) }.getOrNull()
    suspend fun folderErrors(folder: String): List<FileError> =
        runCatching { client?.folderErrors(folder)?.errors }.getOrNull().orEmpty()

    fun addDevice(id: String, name: String) = act("Add device") { addDevice(id.trim().uppercase(), name.trim()) }
    fun removeDevice(id: String) = act("Remove device") { removeDevice(id) }
    fun renameDevice(id: String, name: String) = act("Rename") { setDeviceName(id, name.trim()) }
    fun setDevicePaused(id: String, paused: Boolean) = act("Pause") { setDevicePaused(id, paused) }
    fun ignoreDevice(id: String) = act("Ignore") { ignoreDevice(id) }
    fun ignoreFolder(folder: String, device: String) = act("Ignore") { ignoreFolder(folder, device) }

    fun addFolder(d: FolderDraft) = act("Add folder", done = "Added ${d.label.ifBlank { d.id }}") {
        addFolder(d.id.trim(), d.label.trim(), d.path.trim(), d.devices.toList(), d.type, d.versioning)
    }

    fun removeFolder(id: String) = act("Remove folder") { removeFolder(id) }
    fun setFolderPaused(id: String, paused: Boolean) = act("Pause") { setFolderPaused(id, paused) }
    fun rescan(id: String) = act("Rescan", done = "Rescanning") { scan(id) }
    fun setFolderLabel(id: String, label: String) = act("Rename") { patchFolder(id, buildJsonObject { put("label", label.trim()) }) }
    fun setFolderType(id: String, type: String) = act("Change type") { patchFolder(id, buildJsonObject { put("type", type) }) }
    fun setWatcher(id: String, on: Boolean) = act("Watch for changes") { patchFolder(id, buildJsonObject { put("fsWatcherEnabled", on) }) }
    fun setVersioning(id: String, v: Versioning) = act("Versioning") { setVersioning(id, v) }
    fun setIgnores(id: String, lines: List<String>) = act("Ignore patterns", done = "Ignore patterns saved") { setIgnores(id, lines) }

    /** Adds or removes one device from a folder's share list. */
    fun setShared(folder: Folder, device: String, shared: Boolean) = act("Sharing") {
        val ids = folder.devices.map { it.deviceID }
        setFolderDevices(folder.id, if (shared) (ids + device).distinct() else ids - device)
    }

    fun setRunConditions(transform: (RunConditions) -> RunConditions) {
        viewModelScope.launch { settings.update(transform) }
    }
}
