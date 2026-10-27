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

/** The Revert confirmation for [n] locally changed items, with the verbs agreeing. */
fun revertText(n: Int): String {
    val one = n == 1
    return "${count(n, "item")} changed on this phone ${if (one) "is" else "are"} replaced with the version from your other devices, " +
        "or deleted if ${if (one) "it doesn't" else "they don't"} exist there. This can't be undone."
}

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

/** Heading for a day of changes: "Today", "Yesterday", "3 Oct", "3 Oct 2025"; null for an unreadable time. */
fun dayText(rfc3339: String, now: ZonedDateTime = ZonedDateTime.now(), locale: Locale = Locale.getDefault()): String? {
    val t = runCatching { OffsetDateTime.parse(rfc3339).atZoneSameInstant(now.zone) }.getOrNull() ?: return null
    val day = t.toLocalDate()
    return when {
        day == now.toLocalDate() -> "Today"
        day == now.toLocalDate().minusDays(1) -> "Yesterday"
        t.year == now.year -> t.format(DateTimeFormatter.ofPattern("d MMM", locale))
        else -> t.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }
}

/** Just the 24 h clock time, for rows under a [dayText] heading. */
fun clockText(rfc3339: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
    runCatching { OffsetDateTime.parse(rfc3339).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("")

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

/**
 * A speed limit the core stores in KiB/s, as SI for people: 488 KiB/s is
 * "500 kB/s". kB/s are whole numbers, MB/s keep one decimal.
 */
fun limitSi(kib: Long): Pair<Double, String> {
    val b = kib * 1024.0
    return if (b >= 1_000_000) Math.round(b / 100_000) / 10.0 to "MB/s" else Math.round(b / 1000).toDouble() to "kB/s"
}

/**
 * Back to the core's KiB/s; anything above zero stays at least 1 so it is not
 * read as "no limit".
 */
fun limitKib(value: Double, unit: String): Long {
    if (!(value > 0)) return 0
    val b = value * if (unit == "MB/s") 1_000_000 else 1000
    return Math.round(b / 1024).coerceAtLeast(1)
}

fun limitText(kib: Long): String = if (kib <= 0) "No limit" else limitSi(kib).let { (v, u) -> "${number(v)} $u" }

val COMPRESSIONS = listOf("metadata" to "Metadata only", "always" to "All data", "never" to "Off")

fun compressionHint(c: String): String? = when (c) {
    "metadata" -> "Default"
    "always" -> "Saves data on text and documents, costs battery. Photos and videos gain almost nothing."
    else -> null
}

/** 0 is Syncthing's choice, currently 3 connections. */
fun connectionsText(n: Int): String = when {
    n <= 0 -> "Automatic"
    n == 1 -> "1 connection"
    else -> "$n connections"
}

fun connectionChoices(current: Int): List<Pair<Int, String>> =
    (listOf(0, 1, 2, 3, 4, 8) + current.coerceAtLeast(0)).distinct().map { it to connectionsText(it) }

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

/** The one number each method asks for: its param key, label, upstream default and smallest value (upstream web GUI's min). */
data class VersioningParam(val key: String, val label: String, val default: String, val unit: String, val min: Int = 0)

fun versioningParam(type: String): VersioningParam? = when (type) {
    "trashcan" -> VersioningParam("cleanoutDays", "Keep for (days, 0 = forever)", "0", "days")
    // 0 would delete every version as soon as it is made.
    "simple" -> VersioningParam("keep", "Versions to keep", "5", "versions", min = 1)
    "staggered" -> VersioningParam("maxAge", "Keep for (days, 0 = forever)", "365", "days")
    else -> null
}

/** Staggered stores maxAge in seconds; the screen edits days. */
fun versioningShown(type: String, raw: String?): String {
    val p = versioningParam(type) ?: return ""
    val v = raw ?: return p.default
    return if (p.key == "maxAge") ((v.toLongOrNull() ?: 0) / 86_400).toString() else v
}

/** As the core's strconv reads it: plain ASCII digits. */
fun versioningStored(type: String, shown: String): String {
    val n = (wholeNumber(shown) ?: 0).toLong()
    return (if (versioningParam(type)?.key == "maxAge") n * 86_400 else n).toString()
}

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
