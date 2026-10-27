package eu.stedb.mysyncthing.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.FileError
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderDevice
import eu.stedb.mysyncthing.core.RestClient
import eu.stedb.mysyncthing.core.RestoreResult
import eu.stedb.mysyncthing.core.restoreDatedNow
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
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.DeviceStats
import eu.stedb.mysyncthing.core.DiskChange
import eu.stedb.mysyncthing.core.FileVersion
import eu.stedb.mysyncthing.core.Discovered
import eu.stedb.mysyncthing.core.IgnoredRequests
import eu.stedb.mysyncthing.core.Options
import eu.stedb.mysyncthing.core.DiskSize
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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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

    /**
     * Runs a write that a screen waits for in the ViewModel's scope, so leaving the
     * screen (Back, another tab, rotation) stops only the waiting: a restore is
     * still dated and rescanned, and a save still completes and reports.
     */
    private suspend fun <T> write(block: suspend () -> T): T = viewModelScope.async { block() }.await()

    /** One-off reads for detail screens; null when the core is not running or the call fails. */
    suspend fun ignores(folder: String): List<String>? = runCatching { client?.ignores(folder) }.getOrNull()
    suspend fun folderErrors(folder: String): List<FileError> =
        runCatching { client?.folderErrors(folder)?.errors }.getOrNull().orEmpty()
    suspend fun folderStats(folder: String): FolderStats? = runCatching { client?.folderStats()?.get(folder) }.getOrNull()
    suspend fun deviceStats(device: String): DeviceStats? = runCatching { client?.deviceStats()?.get(device) }.getOrNull()
    suspend fun need(folder: String, perPage: Int): NeedPage? = runCatching { client?.need(folder, 1, perPage) }.getOrNull()
    suspend fun remoteNeed(folder: String, device: String, perPage: Int): List<FileEntry>? =
        runCatching { client?.remoteNeed(folder, device, 1, perPage)?.files.orEmpty() }.getOrNull()
    suspend fun localChanged(folder: String, perPage: Int): List<FileEntry>? =
        runCatching { client?.localChanged(folder, 1, perPage)?.files.orEmpty() }.getOrNull()

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

    /** Adds the device and shares [folders] with it in the same step. */
    fun addDevice(id: String, name: String, folders: List<Folder> = emptyList()) = act("Add device") {
        val device = id.trim().uppercase()
        addDevice(device, name.trim())
        folders.forEach { shareFolder(it, device) }
    }
    private fun patchDevice(id: String, label: String, fields: JsonObjectBuilder.() -> Unit) =
        act(label) { patchDevice(id, buildJsonObject(fields)) }
    /** The core would refuse introducer and auto-accept for an untrusted device, so they go off with it. */
    fun setUntrusted(id: String, on: Boolean) = patchDevice(id, "Untrusted") {
        put("untrusted", on)
        if (on) {
            put("introducer", false)
            put("autoAcceptFolders", false)
        }
    }
    fun setIntroducer(id: String, on: Boolean) = patchDevice(id, "Introducer") { put("introducer", on) }
    fun setAutoAccept(id: String, on: Boolean) = patchDevice(id, "Accept folders") { put("autoAcceptFolders", on) }
    fun setDeviceGroup(id: String, group: String) = patchDevice(id, "Group") { put("group", group.trim()) }
    fun setAddresses(id: String, addresses: List<String>) = patchDevice(id, "Addresses") {
        putJsonArray("addresses") { addresses.forEach { add(JsonPrimitive(it)) } }
    }
    fun setDeviceRecvLimit(id: String, kib: Long) = patchDevice(id, "Download limit") { put("maxRecvKbps", kib) }
    fun setDeviceSendLimit(id: String, kib: Long) = patchDevice(id, "Upload limit") { put("maxSendKbps", kib) }
    fun setCompression(id: String, c: String) = patchDevice(id, "Compression") { put("compression", c) }
    fun setNumConnections(id: String, n: Int) = patchDevice(id, "Connections") { put("numConnections", n) }

    /** The core's latest file changes, newest first; nothing is stored by the app. */
    suspend fun recentChanges(limit: Int): List<RecentChange>? = runCatching {
        val c = client ?: return null
        c.diskEvents(limit).mapNotNull { e ->
            val d = e.data?.let { runCatching { c.json.decodeFromJsonElement<DiskChange>(it) }.getOrNull() } ?: return@mapNotNull null
            RecentChange(e.id, e.time, fromThisPhone = e.type == "LocalChangeDetected", d)
        }.asReversed()
    }.getOrNull()

    suspend fun versions(folder: String): Map<String, List<FileVersion>>? = runCatching { client?.versions(folder) }.getOrNull()

    /** Restores files to the given version times; false when anything failed (the reason goes to the snackbar). */
    suspend fun restoreVersions(folder: String, versions: Map<String, String>): Boolean = write {
        val c = client ?: return@write false
        val result = runCatching {
            val f = c.folders().first { it.id == folder }
            c.restoreDatedNow(f, versions)
        }.getOrElse { RestoreResult(mapOf("" to (it.message ?: "error")), emptyList()) }
        val (errors, undated) = result
        when {
            errors.isNotEmpty() -> {
                val (file, error) = errors.entries.first()
                _message.send("Restore failed${if (file.isNotEmpty()) " for $file" else ""}: $error" + if (errors.size > 1) " (and ${errors.size - 1} more)" else "")
            }
            undated.isNotEmpty() -> _message.send(
                "Restored, but the date of ${undated.first()}" + (if (undated.size > 1) " (and ${undated.size - 1} more)" else "") +
                    " could not be updated, so the restore may not sync",
            )
            else -> _message.send(if (versions.size == 1) "Restored" else "Restored ${count(versions.size, "file")}")
        }
        errors.isEmpty()
    }

    suspend fun defaultFolder(): Folder? = runCatching { client?.defaultFolder() }.getOrNull()
    suspend fun defaultDevice(): Device? = runCatching { client?.defaultDevice() }.getOrNull()
    suspend fun defaultIgnores(): List<String>? = runCatching { client?.defaultIgnores() }.getOrNull()

    /** Changes the defaults for new folders or devices; false when it failed. */
    suspend fun patchDefaults(device: Boolean, label: String, fields: JsonObjectBuilder.() -> Unit): Boolean = write {
        val c = client ?: return@write false
        val body = buildJsonObject(fields)
        runCatching { if (device) c.patchDefaultDevice(body) else c.patchDefaultFolder(body) }
            .onFailure { _message.send("$label failed: ${it.message}") }.isSuccess
    }

    suspend fun saveDefaultIgnores(lines: List<String>): Boolean = write {
        val c = client ?: return@write false
        runCatching { checked("", lines); c.setDefaultIgnores(lines) }
            .onFailure { _message.send("Ignore patterns failed: ${it.message}") }.isSuccess
    }

    suspend fun options(): Options? = runCatching { client?.options() }.getOrNull()
    suspend fun restartRequired(): Boolean = runCatching { client?.restartRequired() }.getOrNull() == true

    /** Changes Syncthing's global options; false when it failed (the reason goes to the snackbar). */
    suspend fun patchOptions(label: String, fields: JsonObjectBuilder.() -> Unit): Boolean = write {
        val c = client ?: return@write false
        runCatching { c.patchOptions(buildJsonObject(fields)) }
            .onFailure { _message.send("$label failed: ${it.message}") }.isSuccess
    }

    fun restartCore() = act("Restart", done = "Restarting Syncthing") { restart() }

    suspend fun discovery(): Map<String, Discovered> = runCatching { client?.discovery() }.getOrNull().orEmpty()
    suspend fun ignoredRequests(): IgnoredRequests? = runCatching { client?.ignoredRequests() }.getOrNull()

    /** Undoes an Ignore; false when it failed (the reason goes to the snackbar). */
    suspend fun unignore(device: String, folder: String? = null): Boolean = write {
        val c = client ?: return@write false
        runCatching { if (folder == null) c.unignoreDevice(device) else c.unignoreFolder(folder, device) }
            .onFailure { _message.send("Unignore failed: ${it.message}") }.isSuccess
    }
    fun removeDevice(id: String) = act("Remove device") { removeDevice(id) }
    fun renameDevice(id: String, name: String) = act("Rename") { setDeviceName(id, name.trim()) }
    fun setDevicePaused(id: String, paused: Boolean) = act("Pause") { setDevicePaused(id, paused) }
    fun ignoreDevice(id: String) = act("Ignore") { ignoreDevice(id) }
    fun ignoreFolder(folder: String, device: String) = act("Ignore") { ignoreFolder(folder, device) }

    /**
     * [paused] keeps a new folder from syncing until its ignore patterns are set.
     * Otherwise the default ignore patterns, if any, are written while it is
     * still paused, so nothing they exclude is synced first.
     */
    fun addFolder(d: FolderDraft, paused: Boolean = false) = act("Add folder", done = "Added ${d.label.ifBlank { d.id }}") {
        val id = d.id.trim()
        val ignores = if (paused) emptyList() else runCatching { defaultIgnores() }.getOrDefault(emptyList())
        val devices = d.devices.map { FolderDevice(it, encryptionPassword = d.passwords[it].orEmpty()) }
        addFolder(id, d.label.trim(), folderPath(d.path), devices, d.type, d.versioning, paused || ignores.isNotEmpty())
        if (paused || ignores.isEmpty()) return@act
        // The folder may take a moment to start after the config change.
        repeat(5) { attempt ->
            val failure = runCatching { setIgnores(id, ignores) }.exceptionOrNull()
            if (failure == null) {
                setFolderPaused(id, false)
                return@act
            }
            if (attempt == 4) throw IllegalStateException("added paused, default ignore patterns not set (${failure.message})")
            delay(500)
        }
    }

    fun removeFolder(id: String) = act("Remove folder") { removeFolder(id) }
    fun setFolderPaused(id: String, paused: Boolean) = act("Pause") { setFolderPaused(id, paused) }
    fun rescan(id: String) = act("Rescan", done = "Rescanning") { scan(id) }
    fun rescanAll() = act("Rescan", done = "Rescanning all folders") { scan(null) }
    fun revert(id: String) = act("Revert", done = "Reverting local changes") { revert(id) }
    fun override(id: String) = act("Override", done = "Overriding changes") { override(id) }
    fun setFolderGroup(id: String, group: String) = act("Group") { patchFolder(id, buildJsonObject { put("group", group.trim()) }) }
    fun setRescanInterval(id: String, seconds: Int) = act("Full rescan") { patchFolder(id, buildJsonObject { put("rescanIntervalS", seconds) }) }
    fun setPullOrder(id: String, order: String) = act("Download order") { patchFolder(id, buildJsonObject { put("order", order) }) }
    fun setMinDiskFree(id: String, size: DiskSize) = act("Keep free") {
        patchFolder(id, buildJsonObject { putJsonObject("minDiskFree") { put("value", size.value); put("unit", size.unit) } })
    }
    fun setFolderLabel(id: String, label: String) = act("Rename") { patchFolder(id, buildJsonObject { put("label", label.trim()) }) }
    fun setFolderType(id: String, type: String) = act("Change type") { patchFolder(id, buildJsonObject { put("type", type) }) }
    fun setWatcher(id: String, on: Boolean) = act("Watch for changes") { patchFolder(id, buildJsonObject { put("fsWatcherEnabled", on) }) }
    fun setVersioning(id: String, v: Versioning) = act("Versioning") { setVersioning(id, v) }

    /** Refuses patterns the core would save and then fail on, before anything is written. */
    private fun checked(folderPath: String, lines: List<String>) {
        SyncthingCore.checkIgnores(folderPath, lines)?.let { throw IllegalArgumentException(it) }
    }

    /** Saves ignore patterns; [resume] unpauses a folder that waited for them. False when it failed. */
    suspend fun saveIgnores(id: String, lines: List<String>, resume: Boolean): Boolean = write {
        val c = client ?: return@write false
        runCatching {
            checked(snapshot.value.folders.find { it.id == id }?.path.orEmpty(), lines)
            c.setIgnores(id, lines)
            if (resume) c.setFolderPaused(id, false)
        }.onFailure { _message.send("Ignore patterns failed: ${it.message}") }.isSuccess
    }

    /**
     * Adds or removes one device from a folder's share list, or changes its
     * encryption [password] (needed for an untrusted device).
     */
    fun setShared(folder: Folder, device: String, shared: Boolean, password: String = "") = act("Sharing") {
        val others = folder.devices.filter { it.deviceID != device }
        val entry = folder.devices.find { it.deviceID == device } ?: FolderDevice(device)
        setFolderDevices(folder.id, if (shared) others + entry.copy(encryptionPassword = password) else others)
    }

    fun setRunConditions(transform: (RunConditions) -> RunConditions) {
        viewModelScope.launch { settings.update(transform) }
    }
}
