package eu.stedb.mysyncthing.ui

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Completion
import eu.stedb.mysyncthing.core.Connections
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.DeviceStats
import eu.stedb.mysyncthing.core.Discovered
import eu.stedb.mysyncthing.core.DiskChange
import eu.stedb.mysyncthing.core.DiskSize
import eu.stedb.mysyncthing.core.FileEntry
import eu.stedb.mysyncthing.core.FileError
import eu.stedb.mysyncthing.core.FileProgress
import eu.stedb.mysyncthing.core.FileVersion
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderDevice
import eu.stedb.mysyncthing.core.FolderStats
import eu.stedb.mysyncthing.core.IgnoredRequests
import eu.stedb.mysyncthing.core.NeedPage
import eu.stedb.mysyncthing.core.Options
import eu.stedb.mysyncthing.core.ProxySettings
import eu.stedb.mysyncthing.core.RestClient
import eu.stedb.mysyncthing.core.RestoreResult
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.Settings
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Summary
import eu.stedb.mysyncthing.core.SyncStatus
import eu.stedb.mysyncthing.core.SyncthingCore
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.UiTextException
import eu.stedb.mysyncthing.core.Versioning
import eu.stedb.mysyncthing.core.messageText
import eu.stedb.mysyncthing.core.plural
import eu.stedb.mysyncthing.core.restoreDatedNow
import eu.stedb.mysyncthing.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
        save { settings.setSetupDone() }
    }

    val movedFromOtherPhone: StateFlow<Boolean> =
        settings.movedFromOtherPhone.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun dismissMovedNote() {
        save { settings.setMovedFromOtherPhone(false) }
    }

    /** Followed by the service, so the screens add no extra event long-poll. */
    val snapshot: StateFlow<Snapshot> = SyncStatus.snapshot
    val summary: StateFlow<Summary> = SyncStatus.summary

    private val _message = Channel<UiText>(Channel.BUFFERED)
    val messages = _message.receiveAsFlow()

    private val client: RestClient? get() = (core.value as? CoreState.Running)?.client

    /** [label] names what failed: "Rename failed: <reason>". */
    private fun act(@StringRes label: Int, done: UiText? = null, block: suspend RestClient.() -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.block() }
                .onSuccess { done?.let { _message.send(it) } }
                .onFailure { _message.send(failed(label, it)) }
        }
    }

    private fun failed(@StringRes label: Int, e: Throwable) = text(R.string.op_failed, text(label), e.messageText())

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

    fun clearErrors() = act(R.string.op_clear) { SyncStatus.clearErrors(this) }

    suspend fun usageConsent(): UsageConsent? = runCatching {
        val c = client ?: return null
        val max = c.status().urVersionMax
        val o = c.options()
        UsageConsent(needsPrompt = o.urAccepted > -1 && o.urSeen < max && o.urAccepted < max, enabled = o.urAccepted > 0)
    }.getOrNull()

    suspend fun usageReport(): String? = runCatching { client?.usageReport() }.getOrNull()
    fun answerUsagePrompt(accept: Boolean) = act(R.string.op_usage_reporting) { answerUsagePrompt(accept) }
    fun setUsageReporting(on: Boolean) = act(R.string.op_usage_reporting) { setUsageReporting(on) }

    /** Adds the device and shares [folders] with it in the same step. */
    fun addDevice(id: String, name: String, folders: List<Folder> = emptyList()) = act(R.string.op_add_device) {
        val device = id.trim().uppercase()
        addDevice(device, name.trim())
        folders.forEach { shareFolder(it, device) }
    }
    private fun patchDevice(id: String, @StringRes label: Int, fields: JsonObjectBuilder.() -> Unit) =
        act(label) { patchDevice(id, buildJsonObject(fields)) }
    /** The core would refuse introducer and auto-accept for an untrusted device, so they go off with it. */
    fun setUntrusted(id: String, on: Boolean) = patchDevice(id, R.string.op_untrusted) {
        put("untrusted", on)
        if (on) {
            put("introducer", false)
            put("autoAcceptFolders", false)
        }
    }
    fun setIntroducer(id: String, on: Boolean) = patchDevice(id, R.string.op_introducer) { put("introducer", on) }
    fun setAutoAccept(id: String, on: Boolean) = patchDevice(id, R.string.op_accept_folders) { put("autoAcceptFolders", on) }
    fun setDeviceGroup(id: String, group: String) = patchDevice(id, R.string.op_group) { put("group", group.trim()) }
    fun setAddresses(id: String, addresses: List<String>) = patchDevice(id, R.string.op_addresses) {
        putJsonArray("addresses") { addresses.forEach { add(JsonPrimitive(it)) } }
    }
    fun setDeviceRecvLimit(id: String, kib: Long) = patchDevice(id, R.string.op_download_limit) { put("maxRecvKbps", kib) }
    fun setDeviceSendLimit(id: String, kib: Long) = patchDevice(id, R.string.op_upload_limit) { put("maxSendKbps", kib) }
    fun setCompression(id: String, c: String) = patchDevice(id, R.string.op_compression) { put("compression", c) }
    fun setNumConnections(id: String, n: Int) = patchDevice(id, R.string.op_connections) { put("numConnections", n) }

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
        }.getOrElse { RestoreResult(mapOf("" to (it.message ?: it.toString())), emptyList()) }
        val (errors, undated) = result
        when {
            errors.isNotEmpty() -> {
                val (file, error) = errors.entries.first()
                val first = if (file.isEmpty()) text(R.string.restore_failed, error) else text(R.string.restore_failed_file, file, error)
                _message.send(if (errors.size > 1) plural(R.plurals.and_more, errors.size - 1, first, errors.size - 1) else first)
            }
            undated.isNotEmpty() -> _message.send(
                if (undated.size > 1) plural(R.plurals.restore_undated_more, undated.size - 1, undated.first(), undated.size - 1)
                else text(R.string.restore_undated, undated.first()),
            )
            else -> _message.send(if (versions.size == 1) text(R.string.restore_done) else count(R.plurals.restore_done_files, versions.size))
        }
        errors.isEmpty()
    }

    suspend fun defaultFolder(): Folder? = runCatching { client?.defaultFolder() }.getOrNull()
    suspend fun defaultDevice(): Device? = runCatching { client?.defaultDevice() }.getOrNull()
    suspend fun defaultIgnores(): List<String>? = runCatching { client?.defaultIgnores() }.getOrNull()

    /** Changes the defaults for new folders or devices; false when it failed. */
    suspend fun patchDefaults(device: Boolean, @StringRes label: Int, fields: JsonObjectBuilder.() -> Unit): Boolean = write {
        val c = client ?: return@write false
        val body = buildJsonObject(fields)
        runCatching { if (device) c.patchDefaultDevice(body) else c.patchDefaultFolder(body) }
            .onFailure { _message.send(failed(label, it)) }.isSuccess
    }

    suspend fun saveDefaultIgnores(lines: List<String>): Boolean = write {
        val c = client ?: return@write false
        runCatching { checked("", lines); c.setDefaultIgnores(lines) }
            .onFailure { _message.send(failed(R.string.op_ignore_patterns, it)) }.isSuccess
    }

    suspend fun options(): Options? = runCatching { client?.options() }.getOrNull()
    suspend fun restartRequired(): Boolean = runCatching { client?.restartRequired() }.getOrNull() == true

    /** Changes Syncthing's global options; false when it failed (the reason goes to the snackbar). */
    suspend fun patchOptions(@StringRes label: Int, fields: JsonObjectBuilder.() -> Unit): Boolean = write {
        val c = client ?: return@write false
        runCatching { c.patchOptions(buildJsonObject(fields)) }
            .onFailure { _message.send(failed(label, it)) }.isSuccess
    }

    fun restartCore() = act(R.string.op_restart, done = text(R.string.done_restarting)) { restart() }

    suspend fun discovery(): Map<String, Discovered> = runCatching { client?.discovery() }.getOrNull().orEmpty()
    suspend fun ignoredRequests(): IgnoredRequests? = runCatching { client?.ignoredRequests() }.getOrNull()

    /** Undoes an Ignore; false when it failed (the reason goes to the snackbar). */
    suspend fun unignore(device: String, folder: String? = null): Boolean = write {
        val c = client ?: return@write false
        runCatching { if (folder == null) c.unignoreDevice(device) else c.unignoreFolder(folder, device) }
            .onFailure { _message.send(failed(R.string.op_unignore, it)) }.isSuccess
    }
    fun removeDevice(id: String) = act(R.string.op_remove_device) { removeDevice(id) }
    fun renameDevice(id: String, name: String) = act(R.string.op_rename) { setDeviceName(id, name.trim()) }
    fun setDevicePaused(id: String, paused: Boolean) = act(R.string.op_pause) { setDevicePaused(id, paused) }
    fun ignoreDevice(id: String) = act(R.string.op_ignore) { ignoreDevice(id) }
    fun ignoreFolder(folder: String, device: String) = act(R.string.op_ignore) { ignoreFolder(folder, device) }

    /**
     * [paused] keeps a new folder from syncing until its ignore patterns are set.
     * Otherwise the default ignore patterns, if any, are written while it is
     * still paused, so nothing they exclude is synced first.
     */
    fun addFolder(d: FolderDraft, paused: Boolean = false) = act(R.string.op_add_folder, done = text(R.string.done_added, d.label.ifBlank { d.id })) {
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
            if (attempt == 4) throw UiTextException(text(R.string.folder_added_paused, failure.messageText()))
            delay(500)
        }
    }

    fun removeFolder(id: String) = act(R.string.op_remove_folder) { removeFolder(id) }
    fun setFolderPaused(id: String, paused: Boolean) = act(R.string.op_pause) { setFolderPaused(id, paused) }
    fun rescan(id: String) = act(R.string.op_rescan, done = text(R.string.done_rescanning)) { scan(id) }
    fun rescanAll() = act(R.string.op_rescan, done = text(R.string.done_rescanning_all)) { scan(null) }
    fun revert(id: String) = act(R.string.op_revert, done = text(R.string.done_reverting)) { revert(id) }
    fun override(id: String) = act(R.string.op_override, done = text(R.string.done_overriding)) { override(id) }
    fun setFolderGroup(id: String, group: String) = act(R.string.op_group) { patchFolder(id, buildJsonObject { put("group", group.trim()) }) }
    fun setRescanInterval(id: String, seconds: Int) = act(R.string.op_full_rescan) { patchFolder(id, buildJsonObject { put("rescanIntervalS", seconds) }) }
    fun setPullOrder(id: String, order: String) = act(R.string.op_download_order) { patchFolder(id, buildJsonObject { put("order", order) }) }
    fun setMinDiskFree(id: String, size: DiskSize) = act(R.string.op_keep_free) {
        patchFolder(id, buildJsonObject { putJsonObject("minDiskFree") { put("value", size.value); put("unit", size.unit) } })
    }
    fun setFolderLabel(id: String, label: String) = act(R.string.op_rename) { patchFolder(id, buildJsonObject { put("label", label.trim()) }) }
    fun setFolderType(id: String, type: String) = act(R.string.op_change_type) { patchFolder(id, buildJsonObject { put("type", type) }) }
    fun setWatcher(id: String, on: Boolean) = act(R.string.op_watch) { patchFolder(id, buildJsonObject { put("fsWatcherEnabled", on) }) }
    fun setVersioning(id: String, v: Versioning) = act(R.string.op_versioning) { setVersioning(id, v) }

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
        }.onFailure { _message.send(failed(R.string.op_ignore_patterns, it)) }.isSuccess
    }

    /**
     * Adds or removes one device from a folder's share list, or changes its
     * encryption [password] (needed for an untrusted device).
     */
    fun setShared(folder: Folder, device: String, shared: Boolean, password: String = "") = act(R.string.op_sharing) {
        val others = folder.devices.filter { it.deviceID != device }
        val entry = folder.devices.find { it.deviceID == device } ?: FolderDevice(device)
        setFolderDevices(folder.id, if (shared) others + entry.copy(encryptionPassword = password) else others)
    }

    fun setRunConditions(transform: (RunConditions) -> RunConditions) {
        save { settings.update(transform) }
    }

    /** The stored run conditions (not [runConditions]'s placeholder before they are read). */
    suspend fun loadRunConditions(): RunConditions = settings.runConditions.first()

    /** Like [setRunConditions], for a form that closes only once it is stored. */
    suspend fun saveRunConditions(transform: (RunConditions) -> RunConditions): Boolean =
        settings.update(transform).also { if (!it) _message.send(NOT_SAVED) }

    /** A settings write; it fails (and changes nothing) when the phone's storage is full. */
    private fun save(write: suspend () -> Boolean) {
        viewModelScope.launch { if (!write()) _message.send(NOT_SAVED) }
    }

    val proxy: StateFlow<ProxySettings?> = settings.proxy.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Stores the proxy and hands it to the bridge; a running core restarts to
     * use it. False when the bridge refused it or it couldn't be stored (the
     * reason goes to the snackbar).
     */
    suspend fun setProxy(p: ProxySettings): Boolean {
        // The bridge waits for a start or stop in progress.
        runCatching { withContext(Dispatchers.IO) { SyncthingCore.setProxy(p) } }.onFailure {
            _message.send(text(R.string.proxy_not_saved, it.messageText()))
            return false
        }
        if (!settings.setProxy(p)) {
            // Back to the stored proxy, so the next start or restart doesn't use one the app forgot.
            runCatching { withContext(Dispatchers.IO) { SyncthingCore.setProxy(settings.proxy.first()) } }
            _message.send(NOT_SAVED)
            return false
        }
        if (client != null) restartCore()
        return true
    }
}

/** Snackbar text for a settings write that failed. */
val NOT_SAVED = text(R.string.not_saved)
