package eu.stedb.mysyncthing.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps starting the core and replacing its setup apart. The service runs its
 * whole start inside [use], from the database check to the core start; an
 * import (ForkImport) runs inside it from stopping the core to its last
 * settings write. A start asked for meanwhile, whatever asked (the app opening,
 * a rotation, boot, a sticky restart), waits and then starts the new setup.
 */
class HomeLock {
    private val mutex = Mutex()

    suspend fun <T> use(block: suspend () -> T): T = mutex.withLock { block() }
}
