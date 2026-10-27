package eu.stedb.mysyncthing.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.plural
import eu.stedb.mysyncthing.core.raw
import eu.stedb.mysyncthing.core.text
import eu.stedb.mysyncthing.core.DiskSize
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** [value] and its [unit] with a no-break space, so a line never ends between them. */
fun withUnit(value: Any, unit: String): String = "$value\u00A0$unit"

/** SI (base-1000) byte sizes. */
fun bytes(n: Long): String {
    if (n < 1000) return withUnit(n, "B")
    val units = "kMGTPE"
    var v = n.toDouble()
    var i = -1
    while (v >= 1000 && i < units.length - 1) { v /= 1000; i++ }
    return withUnit(String.format(Locale.ROOT, "%.1f", v), "${units[i]}B")
}

/** A transfer speed with its unit, e.g. "1.2 MB/s". */
fun rate(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

/** "12 min", "3 h 5 min", "2 d 4 h": the two largest units. */
fun duration(seconds: Long): String {
    val d = seconds / 86_400
    val h = seconds % 86_400 / 3_600
    val m = seconds % 3_600 / 60
    return when {
        d > 0 -> if (h > 0) "${withUnit(d, "d")} ${withUnit(h, "h")}" else withUnit(d, "d")
        h > 0 -> if (m > 0) "${withUnit(h, "h")} ${withUnit(m, "min")}" else withUnit(h, "h")
        m > 0 -> withUnit(m, "min")
        else -> withUnit(seconds, "s")
    }
}

/** [n] with its digits grouped as the phone's language does: "1,204". */
fun grouped(n: Int): String = String.format(Locale.getDefault(), "%,d", n)

/** "1 file", "1,204 files": [id] is a plurals resource whose %1$s takes the grouped count. */
fun count(@PluralsRes id: Int, n: Int): UiText = plural(id, n, grouped(n))

/** The Revert confirmation for [n] locally changed items, with the verbs agreeing. */
fun revertText(n: Int): UiText = plural(R.plurals.revert_text, n, grouped(n))

/**
 * A REST time stamp (RFC 3339) for people, day before month, 24 h: "12:03" today,
 * "yesterday, 22:10", "25 Oct, 12:03" this year, else with the year.
 * Null for Go's zero time, which the core uses for "never".
 */
fun whenText(rfc3339: String, now: ZonedDateTime = ZonedDateTime.now(), locale: Locale = Locale.getDefault()): UiText? {
    val t = runCatching { OffsetDateTime.parse(rfc3339).atZoneSameInstant(now.zone) }.getOrNull() ?: return null
    if (t.year < 2000) return null
    val time = t.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    val day = t.toLocalDate()
    return when {
        day == now.toLocalDate() -> raw(time)
        day == now.toLocalDate().minusDays(1) -> text(R.string.when_yesterday, time)
        t.year == now.year -> raw(t.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", locale)))
        else -> raw(t.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale)))
    }
}

/** An absolute day-first time, e.g. "26 Oct 2026, 19:27", for a file's date. */
fun dateTimeText(epochMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale))

/** Heading for a day of changes: "Today", "Yesterday", "3 Oct", "3 Oct 2025"; null for an unreadable time. */
fun dayText(rfc3339: String, now: ZonedDateTime = ZonedDateTime.now(), locale: Locale = Locale.getDefault()): UiText? {
    val t = runCatching { OffsetDateTime.parse(rfc3339).atZoneSameInstant(now.zone) }.getOrNull() ?: return null
    val day = t.toLocalDate()
    return when {
        day == now.toLocalDate() -> text(R.string.day_today)
        day == now.toLocalDate().minusDays(1) -> text(R.string.day_yesterday)
        t.year == now.year -> raw(t.format(DateTimeFormatter.ofPattern("d MMM", locale)))
        else -> raw(t.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale)))
    }
}

/**
 * The exact 24 h time to the second, for rows under a [dayText] heading, with
 * how long ago within the last hour: "18:02:12 · 5 min ago". "" when unreadable.
 */
fun exactTimeText(rfc3339: String, now: ZonedDateTime = ZonedDateTime.now()): UiText {
    val t = runCatching { OffsetDateTime.parse(rfc3339).atZoneSameInstant(now.zone) }.getOrNull() ?: return raw("")
    val clock = t.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    val ago = java.time.Duration.between(t, now).seconds
    return when {
        ago < 0 || ago >= 3600 -> raw(clock) // a clock set back, or long enough ago for the time alone
        ago < 60 -> text(R.string.exact_time_just_now, clock)
        else -> text(R.string.exact_time_ago, clock, withUnit(ago / 60, "min"))
    }
}

val PULL_ORDERS = listOf(
    "random" to text(R.string.order_random),
    "alphabetic" to text(R.string.order_alphabetic),
    "smallestFirst" to text(R.string.order_smallest_first),
    "largestFirst" to text(R.string.order_largest_first),
    "oldestFirst" to text(R.string.order_oldest_first),
    "newestFirst" to text(R.string.order_newest_first),
)

fun orderLabel(order: String): UiText = PULL_ORDERS.find { it.first == order }?.second ?: raw(order)

/** "Every hour", "Every 6 hours", "Once a day", "Never". */
fun rescanText(seconds: Int): UiText = when {
    seconds <= 0 -> text(R.string.rescan_never)
    seconds == 86_400 -> text(R.string.rescan_daily)
    seconds % 86_400 == 0 -> plural(R.plurals.rescan_every_days, seconds / 86_400)
    seconds % 3_600 == 0 -> plural(R.plurals.rescan_every_hours, seconds / 3_600)
    seconds % 60 == 0 -> text(R.string.rescan_every_minutes, seconds / 60)
    else -> text(R.string.rescan_every_seconds, seconds)
}

/** The usual choices, plus the current value when it is something else. */
fun rescanChoices(current: Int): List<Pair<Int, UiText>> =
    (listOf(3_600, 21_600, 86_400, 0) + current).distinct().map { it to rescanText(it) }

fun rescanHint(seconds: Int): UiText? = when (seconds) {
    3_600 -> text(R.string.hint_default)
    0 -> text(R.string.rescan_never_hint)
    else -> null
}

/** 1.0 as "1", 2.5 as "2.5". */
fun number(v: Double): String = if (v == Math.floor(v) && v < 1e15) v.toLong().toString() else v.toString()

/** "1 %", "5 GB": upstream's units are SI. */
fun diskSizeText(d: DiskSize): String = withUnit(number(d.value), d.unit)

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

fun limitText(kib: Long): UiText = if (kib <= 0) text(R.string.limit_none) else raw(limitSi(kib).let { (v, u) -> withUnit(number(v), u) })

val COMPRESSIONS = listOf(
    "metadata" to text(R.string.compression_metadata),
    "always" to text(R.string.compression_always),
    "never" to text(R.string.compression_never),
)

fun compressionLabel(c: String): UiText = COMPRESSIONS.find { it.first == c }?.second ?: raw(c)

fun compressionHint(c: String): UiText? = when (c) {
    "metadata" -> text(R.string.hint_default)
    "always" -> text(R.string.compression_always_hint)
    else -> null
}

/** 0 is Syncthing's choice, currently 3 connections. */
fun connectionsText(n: Int): UiText = if (n <= 0) text(R.string.connections_auto) else plural(R.plurals.connections, n)

fun connectionChoices(current: Int): List<Pair<Int, UiText>> =
    (listOf(0, 1, 2, 3, 4, 8) + current.coerceAtLeast(0)).distinct().map { it to connectionsText(it) }

/** Syncthing's grouped display form, e.g. ABCDEFG-HIJKLMN. Only the first group is shown in lists. */
fun shortId(id: String): String = id.substringBefore('-')

/**
 * [path] with its leading "/" glued to the first name (U+2060 word joiner): at
 * large font sizes the line breaker would otherwise leave the "/" alone on a line.
 */
fun wrapPath(path: String): String = if (path.length > 1 && path[0] == '/') "/\u2060" + path.substring(1) else path

/** Paths in internal storage are shown relative to it, as the Files app does. */
fun displayPath(path: String): UiText = when {
    path == INTERNAL_STORAGE -> text(R.string.internal_storage)
    path.startsWith("$INTERNAL_STORAGE/") -> raw(path.removePrefix(INTERNAL_STORAGE))
    else -> raw(path)
}

val FOLDER_TYPES = listOf(
    "sendreceive" to text(R.string.folder_type_send_receive),
    "sendonly" to text(R.string.folder_type_send_only),
    "receiveonly" to text(R.string.folder_type_receive_only),
)

fun folderTypeLabel(type: String): UiText = FOLDER_TYPES.find { it.first == type }?.second ?: raw(type)

fun folderTypeHint(type: String): UiText? = when (type) {
    "sendreceive" -> text(R.string.folder_type_send_receive_hint)
    "sendonly" -> text(R.string.folder_type_send_only_hint)
    "receiveonly" -> text(R.string.folder_type_receive_only_hint)
    else -> null
}

/** A folder type with what it means: "Send only. This phone is the source; …". */
fun folderTypeText(type: String): UiText =
    folderTypeHint(type)?.let { text(R.string.label_and_hint, folderTypeLabel(type), it) } ?: folderTypeLabel(type)

/** Versioning methods the native screen offers; "external" stays in the web GUI. */
val VERSIONING_TYPES = listOf(
    "" to text(R.string.versioning_off),
    "trashcan" to text(R.string.versioning_trashcan),
    "simple" to text(R.string.versioning_simple),
    "staggered" to text(R.string.versioning_staggered),
)

fun versioningLabel(type: String): UiText = VERSIONING_TYPES.find { it.first == type }?.second ?: raw(type)

fun versioningHint(type: String): UiText? = when (type) {
    "" -> text(R.string.versioning_off_hint)
    "trashcan" -> text(R.string.versioning_trashcan_hint)
    "simple" -> text(R.string.versioning_simple_hint)
    "staggered" -> text(R.string.versioning_staggered_hint)
    else -> null
}

/**
 * The one number each method asks for: its param key, label, upstream default and smallest value (upstream web GUI's min).
 * [days] when it counts days (0 = forever), else versions.
 */
data class VersioningParam(val key: String, @param:StringRes val label: Int, val default: String, val days: Boolean, val min: Int = 0)

fun versioningParam(type: String): VersioningParam? = when (type) {
    "trashcan" -> VersioningParam("cleanoutDays", R.string.versioning_keep_days, "0", days = true)
    // 0 would delete every version as soon as it is made.
    "simple" -> VersioningParam("keep", R.string.versioning_keep_versions, "5", days = false, min = 1)
    "staggered" -> VersioningParam("maxAge", R.string.versioning_keep_days, "365", days = true)
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

fun versioningSummary(type: String, params: Map<String, String>): UiText {
    val label = versioningLabel(type)
    val p = versioningParam(type) ?: return label
    val shown = versioningShown(type, params[p.key])
    return if (p.days && shown == "0") text(R.string.versioning_summary_forever, label)
    else text(R.string.versioning_summary, label, versioningAmount(p, shown))
}

/** "Forever", "30 days" or "5 versions" for what [p] is set to, [shown] as the screen edits it. */
fun versioningAmount(p: VersioningParam, shown: String): UiText {
    val n = shown.toIntOrNull() ?: 0
    return when {
        p.days && n == 0 -> text(R.string.versioning_forever)
        p.days -> plural(R.plurals.count_days, n, shown)
        else -> plural(R.plurals.count_versions, n, shown)
    }
}

/** "None", "3 rules", or "Loading" while [ignores] is not read yet. */
fun ignoresText(ignores: List<String>?): UiText = when {
    ignores == null -> text(R.string.loading)
    ignores.isEmpty() -> text(R.string.ignores_none)
    else -> count(R.plurals.count_rules, ignores.size)
}
