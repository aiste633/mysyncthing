package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.RestClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Something a screen reads from the core and shows or edits, kept in the view model:
 * a screen recreated by a rotation, dark mode or a language change draws it, and the
 * dialog open on it, on its first frame while [load] refreshes it. Read in the screen
 * only, the first frame would be empty: the dialog would come back late and the
 * scroll position would be lost to a shorter screen.
 *
 * It belongs to the core run that read it: null once that core is no longer running,
 * and a read that a stop or a new start overtook is dropped.
 */
class CoreValue<T : Any>(
    scope: CoroutineScope,
    private val core: StateFlow<CoreState>,
    private val read: suspend RestClient.() -> T,
) {
    private val held = MutableStateFlow<T?>(null)

    /** Null until read, when the read failed, and while the core is not running. */
    val value: StateFlow<T?> = held.asStateFlow()

    init {
        scope.launch { core.collect { if (it !is CoreState.Running) held.value = null } }
    }

    private val client get() = (core.value as? CoreState.Running)?.client

    /** Reads it again and returns it; on failure it becomes null, as it is when the core is not running. */
    suspend fun load(): T? {
        val c = client ?: return null.also { held.value = null }
        val v = try {
            c.read()
        } catch (e: CancellationException) {
            // The screen that asked is gone (left or recreated): keep what is shown.
            throw e
        } catch (_: Exception) {
            null
        }
        if (c === client) held.value = v
        return v
    }

    /** Shows a change at once, before the core has it. */
    fun show(new: T) {
        held.value = new
    }
}
