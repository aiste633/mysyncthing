package eu.stedb.mysyncthing.ui

import java.util.Locale

/** SI (base-1000) byte sizes. */
fun bytes(n: Long): String {
    if (n < 1000) return "$n B"
    val units = "kMGTPE"
    var v = n.toDouble()
    var i = -1
    while (v >= 1000 && i < units.length - 1) { v /= 1000; i++ }
    return String.format(Locale.ROOT, "%.1f %sB", v, units[i])
}

/** Syncthing's grouped display form, e.g. ABCDEFG-HIJKLMN. Only the first group is shown in lists. */
fun shortId(id: String): String = id.substringBefore('-')

/** Paths in internal storage are shown relative to it, as the Files app does. */
fun displayPath(path: String): String = when {
    path == INTERNAL_STORAGE -> "Internal storage"
    path.startsWith("$INTERNAL_STORAGE/") -> path.removePrefix(INTERNAL_STORAGE)
    else -> path
}

val FOLDER_TYPES = listOf(
    "sendreceive" to "Send & receive",
    "sendonly" to "Send only",
    "receiveonly" to "Receive only",
)

fun folderTypeLabel(type: String): String = FOLDER_TYPES.find { it.first == type }?.second ?: type

fun folderTypeHint(type: String): String? = when (type) {
    "sendreceive" -> "Changes go both ways."
    "sendonly" -> "This phone is the source; changes made elsewhere are not applied here."
    "receiveonly" -> "Changes made on this phone are never sent to other devices."
    else -> null
}

/** Versioning methods the native screen offers; "external" stays in the web GUI. */
val VERSIONING_TYPES = listOf(
    "" to "Off",
    "trashcan" to "Trash can",
    "simple" to "Simple",
    "staggered" to "Staggered",
)

fun versioningHint(type: String): String? = when (type) {
    "" -> "Replaced or deleted files are gone."
    "trashcan" -> "Keeps the last version of files changed or deleted on other devices."
    "simple" -> "Keeps a number of old versions of each file."
    "staggered" -> "Keeps more recent versions, fewer older ones, up to a maximum age."
    else -> null
}

/** The one number each method asks for: its param key, label and upstream default. */
data class VersioningParam(val key: String, val label: String, val default: String, val unit: String)

fun versioningParam(type: String): VersioningParam? = when (type) {
    "trashcan" -> VersioningParam("cleanoutDays", "Keep for (days, 0 = forever)", "0", "days")
    "simple" -> VersioningParam("keep", "Versions to keep", "5", "versions")
    "staggered" -> VersioningParam("maxAge", "Keep for (days, 0 = forever)", "365", "days")
    else -> null
}

/** Staggered stores maxAge in seconds; the screen edits days. */
fun versioningShown(type: String, raw: String?): String {
    val p = versioningParam(type) ?: return ""
    val v = raw ?: return p.default
    return if (p.key == "maxAge") ((v.toLongOrNull() ?: 0) / 86_400).toString() else v
}

fun versioningStored(type: String, shown: String): String =
    if (versioningParam(type)?.key == "maxAge") ((shown.toLongOrNull() ?: 0) * 86_400).toString() else shown

fun versioningSummary(type: String, params: Map<String, String>): String {
    val label = VERSIONING_TYPES.find { it.first == type }?.second ?: type
    val p = versioningParam(type) ?: return label
    val n = versioningShown(type, params[p.key])
    return when {
        p.unit == "days" && n == "0" -> "$label · keep forever"
        p.unit == "days" -> "$label · $n days"
        else -> "$label · $n ${p.unit}"
    }
}
