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
import android.os.SystemClock
import eu.stedb.mysyncthing.core.Completion
import eu.stedb.mysyncthing.core.Connections
import eu.stedb.mysyncthing.core.DeviceStats
import eu.stedb.mysyncthing.core.FileEntry
import eu.stedb.mysyncthing.core.FileProgress
import eu.stedb.mysyncthing.core.FolderStats
import eu.stedb.mysyncthing.core.NeedPage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    /** Null until read, so the setup checklist does not flash for users who finished it. */
    val setupDone: StateFlow<Boolean?> = settings.setupDone.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun finishSetup() {
        viewModelScope.launch { settings.setSetupDone() }
    }

    val movedFromOtherPhone: StateFlow<Boolean> =
        settings.movedFromOtherPhone.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun dismissMovedNote() {
        viewModelScope.launch { settings.setMovedFromOtherPhone(false) }
    }

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
    suspend fun folderStats(folder: String): FolderStats? = runCatching { client?.folderStats()?.get(folder) }.getOrNull()
    suspend fun deviceStats(device: String): DeviceStats? = runCatching { client?.deviceStats()?.get(device) }.getOrNull()
    suspend fun need(folder: String, perPage: Int): NeedPage? = runCatching { client?.need(folder, 1, perPage) }.getOrNull()
    suspend fun remoteNeed(folder: String, device: String, perPage: Int): List<FileEntry>? =
        runCatching { client?.remoteNeed(folder, device, 1, perPage)?.files.orEmpty() }.getOrNull()

    /** What [device] still needs from this phone, per folder, for the folders that need anything. */
    suspend fun remoteCompletion(device: String, folders: List<String>): Map<String, Completion> = runCatching {
        val c = client ?: return emptyMap()
        coroutineScope {
            folders.map { f -> async { f to c.completion(device, f) } }.awaitAll()
                .filter { (_, comp) -> comp.needItems > 0 || comp.needBytes > 0 }.toMap()
        }
    }.getOrDefault(emptyMap())

    val downloads: StateFlow<Map<String, Map<String, FileProgress>>> = SyncStatus.downloads

    /**
     * Speeds and listener/discovery health, polled every 2 s while a screen
     * collects it: the core has no event for them. The first sample has no speeds.
     */
    fun liveStats(): Flow<LiveStats> = flow {
        var prev: Pair<Long, Connections>? = null
        while (true) {
            val c = client
            val sample = if (c == null) null else runCatching {
                coroutineScope {
                    val conns = async { c.connections() }
                    c.status() to conns.await()
                }
            }.getOrNull()
            if (sample != null) {
                val (status, conns) = sample
                val now = SystemClock.elapsedRealtime()
                emit(LiveStats.of(status, conns, prev, now))
                prev = now to conns
            }
            delay(2_000)
        }
    }

    fun clearErrors() = act("Clear") { SyncStatus.clearErrors(this) }

    suspend fun usageConsent(): UsageConsent? = runCatching {
        val c = client ?: return null
        val max = c.status().urVersionMax
        val o = c.options()
        UsageConsent(needsPrompt = o.urAccepted > -1 && o.urSeen < max && o.urAccepted < max, enabled = o.urAccepted > 0)
    }.getOrNull()

    suspend fun usageReport(): String? = runCatching { client?.usageReport() }.getOrNull()
    fun answerUsagePrompt(accept: Boolean) = act("Usage reporting") { answerUsagePrompt(accept) }
    fun setUsageReporting(on: Boolean) = act("Usage reporting") { setUsageReporting(on) }

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
