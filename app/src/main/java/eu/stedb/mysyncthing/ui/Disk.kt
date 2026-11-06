package eu.stedb.mysyncthing.ui

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Internal shared storage of the Android user the app runs as: /storage/emulated/0 for
 * the phone's owner, another number in a work profile, Private Space or a cloned app
 * (OnePlus Parallel apps, Xiaomi Second space, Samsung Secure Folder). MainActivity sets
 * it from Android at start; tests keep the owner's.
 */
var internalStorage = "/storage/emulated/0"

/** A storage a folder can be on: internal storage ([name] null), an SD card or a USB stick. */
data class StorageRoot(val path: String, val name: String? = null, val free: Long? = null)

/** A directory in a listing, with the number of entries in it when it could be read. */
data class DirEntry(val name: String, val items: Int? = null)

/** What the folder browser reads and changes on the phone; tests give it a made-up tree. */
interface Disk {
    /** Mounted storages, internal storage first. */
    fun roots(): List<StorageRoot>

    fun isDirectory(path: String): Boolean

    /** The directories in [path], or null when it can't be read. */
    fun directories(path: String): List<DirEntry>?

    fun makeDirectory(path: String): Boolean

    /** Where listings are read: off the main thread, as a storage can be slow. */
    val reads: CoroutineContext get() = Dispatchers.IO
}

/** The phone's storages as Android lists them, read with the All files access the app has. */
class FileDisk(private val context: Context) : Disk {
    override fun roots(): List<StorageRoot> {
        val volumes = context.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { v -> v.directory?.let { StorageRoot(it.path, if (v.isPrimary) null else v.getDescription(context), free(it.path)) } }
        val internal = volumes.filter { it.name == null }.ifEmpty { listOf(StorageRoot(internalStorage, free = free(internalStorage))) }
        return internal + volumes.filter { it.name != null }
    }

    /** What the user can still fill, as Settings > Storage counts it. */
    private fun free(path: String) = runCatching { StatFs(path).availableBytes }.getOrNull()

    override fun isDirectory(path: String) = File(path).isDirectory

    override fun directories(path: String): List<DirEntry>? {
        val dirs = File(path).listFiles()?.filter { it.isDirectory } ?: return null
        // Counting reads each directory once more; skipped where that would be slow.
        val count = dirs.size <= MAX_COUNTED
        return dirs.map { DirEntry(it.name, if (count) it.list()?.size else null) }
    }

    override fun makeDirectory(path: String) = File(path).mkdir()

    private companion object {
        const val MAX_COUNTED = 300
    }
}

/** The browser's [Disk]: the phone's own, unless a test provides one. */
val LocalDisk = staticCompositionLocalOf<Disk?> { null }

@Composable
fun rememberDisk(): Disk {
    val context = LocalContext.current.applicationContext
    return LocalDisk.current ?: remember { FileDisk(context) }
}
