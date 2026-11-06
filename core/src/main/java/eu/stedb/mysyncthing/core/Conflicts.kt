package eu.stedb.mysyncthing.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Whether [path] names a conflict copy: upstream's own test (lib/model isConflict), the base name holds
 * ".sync-conflict-". The core makes `name.sync-conflict-YYYYMMDD-HHMMSS-<short device ID>.ext` from the
 * older of two clashing edits and syncs it like any other file.
 */
fun isConflictCopy(path: String): Boolean = path.substringAfterLast('/').contains(CONFLICT_MARK)

private const val CONFLICT_MARK = ".sync-conflict-"

/** The core's own entries at a folder's root: its marker, and the default place of old file versions. */
private val CORE_DIRS = setOf(".stfolder", ".stversions")

/**
 * The conflict copies in the folder at [root], as paths relative to it with "/", sorted. Reads names
 * only, follows no links, skips what can't be read and the core's own directories at the root (an
 * old version of a conflict copy is not one). Empty when [root] can't be read.
 */
fun findConflictCopies(root: File): List<String> {
    val base = root.toPath()
    val found = mutableListOf<String>()
    try {
        Files.walkFileTree(
            base,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir.parent == base && dir.fileName.toString() in CORE_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && file.fileName.toString().contains(CONFLICT_MARK)) {
                        found += base.relativize(file).joinToString("/")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    } catch (_: IOException) {
        return emptyList()
    } catch (_: SecurityException) {
        return emptyList()
    }
    return found.sorted()
}

/**
 * The conflict copies on this phone, by folder ID: each folder is walked once ([findConflictCopies]),
 * then kept up to date from the core's index updates, which name every file the core scanned or pulled
 * (a copy made, a copy deleted here or elsewhere). Each refresh also checks the known copies are still
 * there, for a copy the core never reports, such as one an ignore pattern hides. The disk is the
 * truth throughout: an update only says which names to look at.
 *
 * Not thread-safe, and it reads the disk: [SyncStatus.follow] calls it from one coroutine on an IO thread.
 */
internal class ConflictTracker(
    private val walk: (File) -> List<String> = ::findConflictCopies,
    private val exists: (File) -> Boolean = File::isFile,
) {
    /** The path each folder was walked at; a folder moved to another path is walked again. */
    private var walked = emptyMap<String, String>()
    private val copies = mutableMapOf<String, Set<String>>()

    /** The copies now, only folders that have some. */
    fun current(): Map<String, List<String>> = copies.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.sorted() }

    /** The folders as the core has them now: new or moved ones are walked, removed ones dropped, known copies re-checked. */
    fun folders(folders: List<Folder>) {
        val now = folders.associate { it.id to it.path }
        copies.keys.retainAll(now.keys)
        copies.replaceAll { id, paths -> paths.filterTo(HashSet()) { exists(File(now.getValue(id), it)) } }
        for ((id, path) in now) {
            if (walked[id] == path) continue
            copies[id] = walk(File(path)).toSet()
            walked = walked + (id to path)
        }
        walked = walked.filterKeys { it in now }
    }

    /** The core updated these files of [folder] in its index; conflict copies among them are looked up on disk. */
    fun changed(folder: String, names: List<String>) {
        val path = walked[folder] ?: return // not walked yet: the walk will find them
        val named = names.filter(::isConflictCopy).ifEmpty { return }
        val set = copies[folder].orEmpty().toMutableSet()
        for (name in named) if (exists(File(path, name))) set += name else set -= name
        copies[folder] = set
    }

    /** Events may have been missed (the core restarted in place): every folder is walked again. */
    fun forget() {
        walked = emptyMap()
        copies.clear()
    }
}

/**
 * A conflict copy's name taken apart: [original] is the file it was copied from (same directory),
 * [madeAt] when the core made it, in the clock of the device that made it, and [device] the short
 * ID of the device whose edit won. Copies made by Syncthing before v0.14 have no device.
 */
data class ConflictCopy(val path: String, val original: String, val madeAt: java.time.LocalDateTime?, val device: String?)

private val CONFLICT_NAME = Regex("""^(.*)\.sync-conflict-(\d{8})-(\d{6})(?:-([A-Z2-7]{7}))?(.*)$""")
private val CONFLICT_TIME = java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss")

/** [path] taken apart, or null when it is not a conflict copy's name as the core makes them. */
fun parseConflictCopy(path: String): ConflictCopy? {
    val dir = path.substringBeforeLast('/', "")
    val m = CONFLICT_NAME.matchEntire(path.substringAfterLast('/')) ?: return null
    val (stem, date, time, device, ext) = m.destructured
    val at = runCatching { java.time.LocalDateTime.parse(date + time, CONFLICT_TIME) }.getOrNull()
    val name = stem + ext
    return ConflictCopy(path, if (dir.isEmpty()) name else "$dir/$name", at, device.ifEmpty { null })
}
