package eu.stedb.mysyncthing.core

import android.os.Environment
import eu.stedb.mysyncthing.stbridge.Stbridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface CoreState {
    data object Stopped : CoreState
    data object Starting : CoreState
    data class Running(val client: RestClient, val address: String, val deviceId: String, val credentials: Credentials) : CoreState
    data class Failed(val message: String) : CoreState
}

/**
 * Process-wide handle on the in-process Go core. Only [SyncthingService]
 * starts and stops it; everything else observes [state].
 */
object SyncthingCore {
    private val _state = MutableStateFlow<CoreState>(CoreState.Stopped)
    val state: StateFlow<CoreState> = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.IO.limitedParallelism(1)

    val version: String get() = Stbridge.version()

    /** Held by the service while it starts the core, and by an import while it replaces the setup. */
    internal val homeLock = HomeLock()

    internal suspend fun start(home: File, credentials: Credentials) = withContext(serial) {
        if (Stbridge.isRunning()) return@withContext
        _state.value = CoreState.Starting
        _state.value = try {
            Stbridge.start(home.absolutePath, credentials.apiKey, credentials.guiUser, credentials.guiPassword)
            val address = Stbridge.guiAddress()
            val client = RestClient(address, credentials.apiKey)
            adoptFolderDefaults(client)
            CoreState.Running(client, address, Stbridge.deviceID(), credentials)
        } catch (e: Exception) {
            CoreState.Failed(e.message ?: e.toString())
        }
    }

    /**
     * Folders the core creates by itself (auto-accepted, or added in the web GUI)
     * use its defaults, whose path "~" means the app's private directory here.
     * Points them at Syncthing/ in shared storage, as the app's own forms do,
     * unless the defaults were already changed. The API may need a moment to
     * come up; a miss is retried at the next start.
     */
    private suspend fun adoptFolderDefaults(client: RestClient) {
        val root = Environment.getExternalStorageDirectory().path + "/Syncthing"
        repeat(5) {
            if (runCatching { client.adoptFolderDefaults(root) }.isSuccess) return
            delay(500)
        }
    }

    internal suspend fun stop() = withContext(serial) {
        Stbridge.stop()
        _state.value = CoreState.Stopped
    }

    /** Called when the core exited on its own (e.g. fatal error). */
    internal fun checkAlive() {
        val s = _state.value
        if (s is CoreState.Running && !Stbridge.isRunning()) {
            _state.value = CoreState.Failed(Stbridge.lastError().ifEmpty { "Syncthing stopped" })
        }
    }
}
