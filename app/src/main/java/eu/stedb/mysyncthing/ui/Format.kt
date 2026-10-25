package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.DiskSize
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

/** A transfer speed with its unit, e.g. "1.2 MB/s". */
fun rate(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

/** "12 min", "3 h 5 min", "2 d 4 h": the two largest units. */
fun duration(seconds: Long): String {
    val d = seconds / 86_400
    val h = seconds % 86_400 / 3_600
    val m = seconds % 3_600 / 60
    return when {
        d > 0 -> if (h > 0) "$d d $h h" else "$d d"
        h > 0 -> if (m > 0) "$h h $m min" else "$h h"
        m > 0 -> "$m min"
        else -> "$seconds s"
    }
}

/** "1 file", "1,204 files"; digits grouped as the phone's language does. */
fun count(n: Int, word: String): String =
    String.format(Locale.getDefault(), "%,d", n) + " " + if (n == 1) word else word + "s"

/**
 * A REST time stamp (RFC 3339) for people, day before month, 24 h: "12:03" today,
 * "yesterday, 22:10", "25 Oct, 12:03" this year, else with the year.
 * Null for Go's zero time, which the core uses for "never".
 */
fun whenText(rfc3339: String, now: ZonedDateTime = ZonedDateTime.now(), locale: Locale = Locale.getDefault()): String? {
    val t = runCatching { OffsetDateTime.parse(rfc3339).atZoneSameInstant(now.zone) }.getOrNull() ?: return null
    if (t.year < 2000) return null
    val time = t.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    val day = t.toLocalDate()
    return when {
        day == now.toLocalDate() -> time
        day == now.toLocalDate().minusDays(1) -> "yesterday, $time"
        t.year == now.year -> t.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", locale))
        else -> t.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale))
    }
}

val PULL_ORDERS = listOf(
    "random" to "Random",
    "alphabetic" to "Alphabetical",
    "smallestFirst" to "Smallest first",
    "largestFirst" to "Largest first",
    "oldestFirst" to "Oldest first",
    "newestFirst" to "Newest first",
)

fun orderLabel(order: String): String = PULL_ORDERS.find { it.first == order }?.second ?: order

/** "Every hour", "Every 6 hours", "Once a day", "Never". */
fun rescanText(seconds: Int): String = when {
    seconds <= 0 -> "Never"
    seconds == 86_400 -> "Once a day"
    seconds % 86_400 == 0 -> "Every ${seconds / 86_400} days"
    seconds == 3_600 -> "Every hour"
    seconds % 3_600 == 0 -> "Every ${seconds / 3_600} hours"
    seconds % 60 == 0 -> "Every ${seconds / 60} min"
    else -> "Every $seconds s"
}

/** The usual choices, plus the current value when it is something else. */
fun rescanChoices(current: Int): List<Pair<Int, String>> =
    (listOf(3_600, 21_600, 86_400, 0) + current).distinct().map { it to rescanText(it) }

fun rescanHint(seconds: Int): String? = when (seconds) {
    3_600 -> "Default"
    0 -> "Only changes that watching sees are synced"
    else -> null
}

/** 1.0 as "1", 2.5 as "2.5". */
fun number(v: Double): String = if (v == Math.floor(v) && v < 1e15) v.toLong().toString() else v.toString()

/** "1 %", "5 GB": upstream's units are SI. */
fun diskSizeText(d: DiskSize): String = "${number(d.value)} ${d.unit}"

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
