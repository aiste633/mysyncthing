package eu.stedb.mysyncthing.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.RestClient
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.Settings
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.SyncStatus
import eu.stedb.mysyncthing.core.SyncthingCore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    val core: StateFlow<CoreState> = SyncthingCore.state
    val runConditions: StateFlow<RunConditions> =
        settings.runConditions.stateIn(viewModelScope, SharingStarted.Eagerly, RunConditions())

    /** Followed by the service, so the screens add no extra event long-poll. */
    val snapshot: StateFlow<Snapshot> = SyncStatus.snapshot

    private val _message = Channel<String>(Channel.BUFFERED)
    val messages = _message.receiveAsFlow()

    private val client: RestClient? get() = (core.value as? CoreState.Running)?.client

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
