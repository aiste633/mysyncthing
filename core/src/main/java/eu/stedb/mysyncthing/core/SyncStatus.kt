package eu.stedb.mysyncthing.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the screens, the notification and the widget show, refreshed from REST on relevant events. */
data class Snapshot(
    val loaded: Boolean = false,
    val myId: String = "",
    val devices: List<Device> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val folderStatus: Map<String, FolderStatus> = emptyMap(),
    val connections: Map<String, Connection> = emptyMap(),
    val completion: Map<String, Completion> = emptyMap(),
    val pendingDevices: Map<String, PendingDevice> = emptyMap(),
    val pendingFolders: Map<String, PendingFolder> = emptyMap(),
)

/**
 * Process-wide view of the running core. [SyncthingService] follows the event
 * stream while the core runs, so the app, notification and widget share one
 * long-poll instead of each opening their own.
 */
object SyncStatus {
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _summary = MutableStateFlow(Summary(SyncState.Stopped, "Stopped"))
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    internal fun publish(summary: Summary) {
        _summary.value = summary
    }

    /** Refreshes once, then on every relevant event, at most once a second. Returns only when cancelled. */
    internal suspend fun follow(client: RestClient) {
        var since = 0L
        val refresh = Channel<Unit>(Channel.CONFLATED)
        try {
            coroutineScope {
                launch {
                    for (r in refresh) {
                        runCatching { _snapshot.value = load(client) }
                        delay(1_000)
                    }
                }
                refresh.trySend(Unit)
                while (true) {
                    try {
                        val events = client.events(since)
                        if (events.isNotEmpty()) since = events.last().id
                        if (events.any { it.type in REFRESH_EVENTS }) refresh.trySend(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        delay(5_000)
                    }
                }
            }
        } finally {
            _snapshot.value = Snapshot()
        }
    }

    private suspend fun load(c: RestClient): Snapshot = coroutineScope {
        val myId = async { c.status().myID }
        val devices = async { c.devices() }
        val folders = async { c.folders() }
        val connections = async { c.connections().connections }
        val pendingDevices = async { c.pendingDevices() }
        val pendingFolders = async { c.pendingFolders() }
        val me = myId.await()
        val folderList = folders.await()
        val deviceList = devices.await().filter { it.deviceID != me }
        val status = folderList.map { f -> async { f.id to runCatching { c.folderStatus(f.id) }.getOrDefault(FolderStatus()) } }
        val completion = deviceList.map { d -> async { d.deviceID to runCatching { c.completion(d.deviceID) }.getOrDefault(Completion()) } }
        Snapshot(
            loaded = true,
            myId = me,
            devices = deviceList,
            folders = folderList,
            folderStatus = status.awaitAll().toMap(),
            connections = connections.await(),
            completion = completion.awaitAll().toMap(),
            pendingDevices = pendingDevices.await(),
            pendingFolders = pendingFolders.await(),
        )
    }
}

/** Overall state, in the order the notification and widget rank them. */
enum class SyncState { Stopped, Problem, Paused, NeedsInput, Syncing, UpToDate }

/**
 * One-line status for the notification and the widget. [connected] holds the
 * names of connected devices; [progress] is 0..100 while syncing.
 */
data class Summary(
    val state: SyncState,
    val text: String,
    val progress: Int? = null,
    val connected: List<String> = emptyList(),
)

private val SYNCING_STATES = setOf("syncing", "sync-preparing")

/**
 * Ranks what the user most needs to know: errors first, then deliberate pauses,
 * then requests waiting for them, then sync progress.
 */
fun summarize(core: CoreState, s: Snapshot, blockedBy: String?, online: Boolean): Summary {
    when (core) {
        is CoreState.Failed -> return Summary(SyncState.Problem, "Stopped with an error")
        CoreState.Stopped -> return Summary(SyncState.Stopped, "Stopped")
        CoreState.Starting -> return Summary(SyncState.Syncing, "Starting")
        is CoreState.Running -> if (!s.loaded) return Summary(SyncState.Syncing, "Starting")
    }
    if (blockedBy != null) return Summary(SyncState.Paused, "Paused: $blockedBy")
    if (!online) return Summary(SyncState.Problem, "No network")

    val connected = s.devices.filter { s.connections[it.deviceID]?.connected == true }
    val names = connected.map { it.name.ifEmpty { it.deviceID.substringBefore('-') } }
    fun result(state: SyncState, text: String, progress: Int? = null) = Summary(state, text, progress, names)

    val active = s.folders.filter { !it.paused }
    val failing = active.mapNotNull { f -> s.folderStatus[f.id]?.takeIf { it.errors > 0 || it.state == "error" } }
    if (failing.isNotEmpty()) {
        val items = failing.sumOf { it.errors }
        return result(SyncState.Problem, if (items > 0) "$items ${plural(items, "item")} failed to sync" else "Folder error")
    }

    val requests = s.pendingDevices.size + s.pendingFolders.values.sumOf { it.offeredBy.size }
    if (requests > 0) {
        val text = when {
            requests > 1 -> "$requests requests waiting"
            s.pendingDevices.isNotEmpty() -> "New device wants to connect"
            else -> "New folder shared with you"
        }
        return result(SyncState.NeedsInput, text)
    }
    if (s.devices.isEmpty()) return result(SyncState.NeedsInput, "No devices added")
    if (s.folders.isEmpty()) return result(SyncState.NeedsInput, "No folders added")
    if (s.devices.all { it.paused } || active.isEmpty()) return result(SyncState.Paused, "Paused")
    if (connected.isEmpty()) return result(SyncState.Problem, "Not connected")

    val statuses = active.mapNotNull { s.folderStatus[it.id] }
    val need = statuses.sumOf { it.needBytes }
    val global = statuses.sumOf { it.globalBytes }
    if (need > 0 || statuses.any { it.state in SYNCING_STATES }) {
        val pct = if (global > 0) ((global - need) * 100 / global).toInt().coerceIn(0, 99) else null
        return result(SyncState.Syncing, pct?.let { "Syncing $it %" } ?: "Syncing", pct)
    }
    val remote = connected.mapNotNull { d -> s.completion[d.deviceID]?.takeIf { it.needBytes > 0 } }
    if (remote.isNotEmpty()) {
        val pct = remote.minOf { it.completion }.toInt().coerceIn(0, 99)
        return result(SyncState.Syncing, "Syncing $pct %", pct)
    }
    return result(SyncState.UpToDate, "Up to date")
}

/** The notification line: state, then who it is connected to. */
fun Summary.line(): String = when {
    connected.size == 1 -> "$text · ${connected.single()}"
    connected.size > 1 -> "$text · ${connected.size} devices"
    else -> text
}

private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
