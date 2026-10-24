package eu.stedb.mysyncthing.core

import eu.stedb.mysyncthing.stbridge.Stbridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    internal suspend fun start(home: File, credentials: Credentials) = withContext(serial) {
        if (Stbridge.isRunning()) return@withContext
        _state.value = CoreState.Starting
        _state.value = try {
            Stbridge.start(home.absolutePath, credentials.apiKey, credentials.guiUser, credentials.guiPassword)
            val address = Stbridge.guiAddress()
            CoreState.Running(RestClient(address, credentials.apiKey), address, Stbridge.deviceID(), credentials)
        } catch (e: Exception) {
            CoreState.Failed(e.message ?: e.toString())
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
