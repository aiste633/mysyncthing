package eu.stedb.mysyncthing.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stedb.mysyncthing.core.Completion
import eu.stedb.mysyncthing.core.Connection
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderStatus
import eu.stedb.mysyncthing.core.PendingDevice
import eu.stedb.mysyncthing.core.PendingFolder
import eu.stedb.mysyncthing.core.REFRESH_EVENTS
import eu.stedb.mysyncthing.core.RestClient
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.Settings
import eu.stedb.mysyncthing.core.SyncthingCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the native screens show, refreshed from REST on relevant events. */
data class Snapshot(
    val myId: String = "",
    val devices: List<Device> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val folderStatus: Map<String, FolderStatus> = emptyMap(),
    val connections: Map<String, Connection> = emptyMap(),
    val completion: Map<String, Completion> = emptyMap(),
    val pendingDevices: Map<String, PendingDevice> = emptyMap(),
    val pendingFolders: Map<String, PendingFolder> = emptyMap(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    val core: StateFlow<CoreState> = SyncthingCore.state
    val runConditions: StateFlow<RunConditions> =
        settings.runConditions.stateIn(viewModelScope, SharingStarted.Eagerly, RunConditions())

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _message = Channel<String>(Channel.BUFFERED)
    val messages = _message.receiveAsFlow()

    private val client: RestClient? get() = (core.value as? CoreState.Running)?.client

    init {
        viewModelScope.launch {
            core.collectLatest { state ->
                if (state is CoreState.Running) follow(state.client)
            }
        }
    }

    /** Refresh once, then on every relevant event; the long-poll keeps this cheap. */
    private suspend fun follow(client: RestClient) {
        var since = 0L
        val refresh = Channel<Unit>(Channel.CONFLATED)
        coroutineScope {
            launch { for (r in refresh) runCatching { _snapshot.value = load(client) } }
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

    private fun act(label: String, block: suspend RestClient.() -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            runCatching { c.block() }.onFailure { _message.send("$label failed: ${it.message}") }
        }
    }

    fun addDevice(id: String, name: String) = act("Add device") { addDevice(id.trim().uppercase(), name.trim()) }
    fun removeDevice(id: String) = act("Remove device") { removeDevice(id) }
    fun setDevicePaused(id: String, paused: Boolean) = act("Pause") { setDevicePaused(id, paused) }
    fun dismissPendingDevice(id: String) = act("Dismiss") { dismissPendingDevice(id) }

    fun addFolder(id: String, label: String, path: String, devices: List<String>) =
        act("Add folder") { addFolder(id.trim(), label.trim(), path.trim(), devices) }

    fun removeFolder(id: String) = act("Remove folder") { removeFolder(id) }
    fun setFolderPaused(id: String, paused: Boolean) = act("Pause") { setFolderPaused(id, paused) }
    fun rescan(id: String) = act("Rescan") { scan(id) }

    fun setRunConditions(transform: (RunConditions) -> RunConditions) {
        viewModelScope.launch { settings.update(transform) }
    }
}
