package eu.stedb.mysyncthing.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** More restored files than this are rescanned with their whole folder, to keep the request short. */
private const val MAX_SCAN_SUBS = 100

/**
 * Restores files to the given version times, then dates them now and rescans them.
 *
 * The core puts a version back with its archived date. Trash can dates versions when it
 * archives them, which is often within the folder's mod-time window (2 s on Android shared
 * storage) of the file the restore replaces. With the same size the scanner then sees no
 * change, and the restore never syncs (an upstream bug, issue #15). A restore is a new
 * change, so with every versioner it gets the restore time.
 *
 * Returns the restore errors by file, like [RestClient.restoreVersions], and the restored
 * files whose date could not be changed.
 */
suspend fun RestClient.restoreDatedNow(folder: Folder, versions: Map<String, String>): RestoreResult {
    val errors = restoreVersions(folder.id, versions)
    val restored = versions.keys - errors.keys
    if (restored.isEmpty()) return RestoreResult(errors, emptyList())
    val undated = withContext(Dispatchers.IO) { dateNow(File(folder.path), restored, System.currentTimeMillis()) }
    scan(folder.id, if (restored.size > MAX_SCAN_SUBS) emptyList() else restored.toList())
    return RestoreResult(errors, undated)
}

data class RestoreResult(val errors: Map<String, String>, val undated: List<String>)

/** Sets the modification time of each file (a path in [root]) to [now]; returns the ones that failed. */
internal fun dateNow(root: File, files: Collection<String>, now: Long): List<String> =
    files.filterNot { File(root, it).setLastModified(now) }
