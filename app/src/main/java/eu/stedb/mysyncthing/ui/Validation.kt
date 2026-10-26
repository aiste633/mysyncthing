package eu.stedb.mysyncthing.ui

// Form checks: each says why a value can't be saved, or returns null when it
// can. Where the core has a rule for a value, the check follows it (Syncthing
// v2.1.7, file named per check), so the app neither sends what the core
// refuses nor what it takes and then fails on. docs/input-validation.md lists
// every field with its check.

fun requiredError(s: String): String? = if (s.isBlank()) "Required" else null

/**
 * [s] with every decimal digit as ASCII: the core reads numbers with Go's
 * strconv, which takes only 0-9, while keyboards for some languages type ٣ or ۳.
 */
private fun asciiDigits(s: String): String =
    if (s.all { it < '\u0080' }) s else buildString { s.forEach { append(if (it.isDigit()) '0' + it.digitToInt() else it) } }

/** A whole number as typed, in any script's digits, without sign or separators; null when it isn't one or doesn't fit an Int. */
fun wholeNumber(s: String): Int? = asciiDigits(s.trim()).takeIf { WHOLE.matches(it) }?.toIntOrNull()

private val WHOLE = Regex("\\d+")

fun wholeNumberError(s: String, min: Int = 0): String? =
    if ((wholeNumber(s) ?: Int.MIN_VALUE) >= min) null else "A whole number, $min or more"

/** A plain decimal typed with either separator; null when it isn't one. No signs, exponents, NaN or Infinity. */
fun decimal(s: String): Double? =
    asciiDigits(s.trim()).takeIf { DECIMAL.matches(it) }?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

private val DECIMAL = Regex("\\d+([.,]\\d*)?|[.,]\\d+")

/** The largest speed limit the core can store, 2 TB/s, as its KiB/s are an int32. */
const val MAX_LIMIT_BYTES = 2e12

/** An amount in [unit]; [emptyOk] when empty means none, as for speed limits. */
fun amountError(s: String, unit: String, emptyOk: Boolean = false): String? {
    if (emptyOk && s.isBlank()) return null
    val v = decimal(s) ?: return if (s.trim().startsWith("-")) "0 or more" else "Enter a number"
    return when {
        unit == "%" && v > 100 -> "Up to 100 %"
        unit == "kB/s" && v * 1e3 > MAX_LIMIT_BYTES -> "Up to ${number(MAX_LIMIT_BYTES / 1e3)} kB/s"
        unit == "MB/s" && v * 1e6 > MAX_LIMIT_BYTES -> "Up to ${number(MAX_LIMIT_BYTES / 1e6)} MB/s"
        else -> null
    }
}

/**
 * Where a folder can be on the phone: shared storage or an SD card, so never
 * the app's own files (which hold this device's key). Not the same as, inside
 * or around another folder, which would sync the same files twice.
 */
fun folderPathError(path: String, others: Collection<String> = emptyList()): String? {
    val p = folderPath(path)
    val inside = { a: String, b: String -> a == b || a.startsWith("$b/") }
    return when {
        p.isEmpty() -> "Required"
        !p.startsWith("/") -> "A full path, starting with /"
        p.any { it.isISOControl() } -> "No line breaks or control characters"
        p.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." } -> "No empty, . or .. parts"
        !inside(p, "/storage") || p == "/storage" || p == "/storage/emulated" -> "In the phone's storage or on an SD card (/storage/…)"
        others.map(::folderPath).any { inside(p, it) || inside(it, p) } -> "Another folder is already here, or inside it"
        else -> null
    }
}

/** A folder path as saved: trimmed, without a trailing "/", and /sdcard, the old name of internal storage, spelled out. */
fun folderPath(path: String): String {
    val p = path.trim().trimEnd('/')
    val alias = listOf("/sdcard", "/storage/self/primary").find { p == it || p.startsWith("$it/") } ?: return p
    return INTERNAL_STORAGE + p.removePrefix(alias)
}

/**
 * Folder IDs: any text upstream, but the REST API names a folder by its ID in
 * the URL, which can't carry "/", "." or ".." (TestFolderIDsInRESTPaths).
 */
fun folderIdError(id: String, existing: Collection<String>): String? {
    val t = id.trim()
    return when {
        t.isEmpty() -> "Required"
        '/' in t -> "No /"
        t == "." || t == ".." -> "Not . or .."
        t.any { it.isISOControl() } -> "No control characters"
        t in existing -> "Another folder already has this ID"
        else -> null
    }
}

/**
 * A folder name as one safe directory name, or null when nothing usable is
 * left. lib/fs/util.go SanitizePath, plus: never "." or "..", which name the
 * folder above, and at most 255 bytes, the longest name Android's file systems take.
 */
fun safeName(s: String): String? {
    val b = StringBuilder()
    var prev = ' '
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        val c = if (!isPrint(cp) || cp == 0xFFFD || cp in DISALLOWED) ' '.code else cp
        if (!(c == ' '.code && prev == ' ')) b.appendCodePoint(c)
        prev = if (c == ' '.code) ' ' else 'x'
    }
    var name = b.toString().trim()
    if (name.substringBefore('.').uppercase() in RESERVED) name = "-$name"
    while (name.toByteArray().size > 255) name = name.dropLast(if (name.last().isLowSurrogate()) 2 else 1).trimEnd()
    return name.takeIf { it.isNotEmpty() && it != "." && it != ".." }
}

/** Go's unicode.IsPrint: letters, marks, numbers, punctuation, symbols and the ASCII space. */
private fun isPrint(cp: Int): Boolean = cp == ' '.code || when (Character.getType(cp).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
    Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
    -> true
    else -> false
}

private val DISALLOWED = "'/\\[]{};:!@$%&^#<>:\"|?*".map { it.code }.toSet()
private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (0..9).flatMap { listOf("COM$it", "LPT$it") }

/** Where an offered folder goes unless the user picks another place: its name, else its ID, as one directory in [base]. */
fun suggestedPath(label: String, base: String, id: String = ""): String = "$base/" + (safeName(label) ?: safeName(id) ?: "Folder")

private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/**
 * The device ID as the core writes it (8 groups of 7, check characters
 * included), or null when the core would refuse it. lib/protocol/deviceid.go,
 * plus surrounding whitespace as pasted: "=", dashes and spaces are dropped, case is ignored, 0, 1 and 8 are read as
 * O, I and B, and the check character ending every 14 must match; an old
 * 52-character ID without them still counts.
 */
fun deviceId(s: String): String? {
    val id = s.trim().trim('=').uppercase().replace('0', 'O').replace('1', 'I').replace('8', 'B').replace("-", "").replace(" ", "")
    if (id.any { it !in BASE32 }) return null
    val data = when (id.length) {
        56 -> {
            val groups = id.chunked(14)
            if (groups.any { luhn32(it.dropLast(1)) != it.last() }) return null
            groups.joinToString("") { it.dropLast(1) }
        }
        52 -> id
        else -> return null
    }
    // Decoded and encoded again, as the core does: the last character's spare bits drop.
    val canonical = base32(unbase32(data))
    return (0 until 4).joinToString("") { g -> canonical.substring(g * 13, g * 13 + 13).let { it + luhn32(it) } }.chunked(7).joinToString("-")
}

/** lib/protocol/luhn.go: not the textbook Luhn mod N, but what every Syncthing ID uses. */
private fun luhn32(s: String): Char {
    var factor = 1
    var sum = 0
    for (c in s) {
        val addend = factor * BASE32.indexOf(c)
        factor = if (factor == 2) 1 else 2
        sum += addend / 32 + addend % 32
    }
    return BASE32[(32 - sum % 32) % 32]
}

/** 52 base32 characters to the ID's 32 bytes. */
private fun unbase32(s: String): ByteArray {
    val out = ByteArray(32)
    var bits = 0
    var acc = 0L
    var n = 0
    for (c in s) {
        acc = (acc shl 5) or BASE32.indexOf(c).toLong()
        bits += 5
        if (bits >= 8 && n < 32) {
            bits -= 8
            out[n++] = (acc shr bits).toByte()
        }
    }
    return out
}

private fun base32(b: ByteArray): String = buildString {
    var bits = 0
    var acc = 0
    for (byte in b) {
        acc = (acc shl 8) or (byte.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            append(BASE32[(acc shr bits) and 31])
        }
    }
    if (bits > 0) append(BASE32[(acc shl (5 - bits)) and 31])
}

/** Scheme, host and port of an address URL, split as Go's url.Parse and net.SplitHostPort do; null when malformed. */
private data class HostPort(val scheme: String, val host: String, val port: Int?, val rest: String)

private val URL_HEAD = Regex("^([a-z][a-z0-9+.-]*)://([^/?#\\s]*)([^\\s]*)$")

private fun hostPort(s: String): HostPort? {
    val (scheme, authority, rest) = URL_HEAD.matchEntire(s)?.destructured ?: return null
    if ('@' in authority) return null
    val (host, port) = when {
        authority.startsWith("[") -> {
            val end = authority.indexOf(']')
            val after = authority.substring(end + 1)
            if (end < 0 || !(after.isEmpty() || after.startsWith(":"))) return null
            (authority.substring(1, end).takeIf { IP6.matches(it) } ?: return null) to after.takeIf { it.isNotEmpty() }?.substring(1)
        }
        authority.count { it == ':' } > 1 -> return null
        ':' in authority -> authority.substringBefore(':') to authority.substringAfter(':')
        else -> authority to null
    }
    if (host.isNotEmpty() && !authority.startsWith("[") && !HOST.matches(host)) return null
    // "host:" counts as no port, as in Go.
    val p = port?.takeIf { it.isNotEmpty() }?.let { if (PORT.matches(it) && it.toInt() <= 65535) it.toInt() else return null }
    return HostPort(scheme, host, p, rest)
}

private val HOST = Regex("[A-Za-z0-9._-]+")
private val PORT = Regex("[0-9]{1,5}")
private val IP6 = Regex("[0-9A-Fa-f:.]+")

/**
 * A device address the core can dial (lib/connections): tcp or quic to a host
 * with an optional port (22000 when left out), or a relay URL.
 */
fun isDeviceAddress(s: String): Boolean {
    val a = hostPort(s) ?: return false
    return a.host.isNotEmpty() && a.port != 0 && when (a.scheme) {
        "tcp", "tcp4", "tcp6", "quic", "quic4", "quic6" -> a.rest.isEmpty() || a.rest == "/"
        "relay" -> a.port != null && a.rest.isRelayQuery()
        else -> false
    }
}

/** A bare host or host:port is taken as TCP. */
fun normalizeAddress(a: String): String = a.trim().let { if ("://" in it || it.isEmpty()) it else "tcp://${it}" }

/**
 * An address to listen on (lib/connections/service.go): the core skips any
 * that isn't in its canonical form, and TCP and QUIC need a port (0 picks
 * one), on all interfaces when the host is left out.
 */
fun isListenAddress(s: String): Boolean {
    val a = hostPort(s) ?: return false
    return when (a.scheme) {
        "tcp", "tcp4", "tcp6", "quic", "quic4", "quic6" -> a.port != null && (a.rest.isEmpty() || a.rest == "/")
        "relay" -> a.host.isNotEmpty() && a.port != null && a.port != 0 && a.rest.isRelayQuery()
        "dynamic+http", "dynamic+https" -> a.host.isNotEmpty() && a.port != 0
        else -> false
    }
}

/** A relay URL's tail: nothing, or options after "?" or "/?", whose id (if any) must be a device ID. */
private fun String.isRelayQuery(): Boolean = removePrefix("/").let { it.isEmpty() || (it.startsWith("?") && queryIdValid(it.substring(1))) }

private fun queryIdValid(query: String): Boolean = query.split('&').filter { it.startsWith("id=") }.all {
    runCatching { deviceId(java.net.URLDecoder.decode(it.removePrefix("id="), "UTF-8")) }.getOrNull() != null
}

/**
 * A global discovery server (lib/discover/global.go): https with a host, an
 * optional port and options such as ?id=<the server's device ID> or ?insecure.
 */
fun isDiscoveryServer(s: String): Boolean {
    val a = hostPort(s.trim()) ?: return false
    if (a.scheme != "https" || a.host.isEmpty() || a.port == 0) return false
    val query = a.rest.substringAfter('?', "")
    return a.rest.substringBefore('?').let { it.isEmpty() || it.startsWith("/") } && queryIdValid(query)
}
