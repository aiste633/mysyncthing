package eu.stedb.mysyncthing.ui

// Form checks: each says why a value can't be saved, or returns null when it
// can. Where the core has a rule for a value, the check follows it (Syncthing
// v2.1.7, file named per check), so the app neither sends what the core
// refuses nor what it takes and then fails on. docs/input-validation.md lists
// every field with its check.

fun requiredError(s: String): String? = if (s.isBlank()) "Required" else null

/**
 * Digits other than 0-9, as keyboards for some languages type them (٣, ۳, ३).
 * The core reads numbers with Go's strconv, which takes only 0-9; Kotlin would
 * read these too, so they are refused, not converted behind the user's back.
 */
private fun otherDigits(s: String): Boolean = s.any { it.isDigit() && it !in '0'..'9' }

private const val USE_ASCII_DIGITS = "Use the digits 0-9"

/** A whole number in 0-9 digits, without sign or separators; null when it isn't one or doesn't fit an Int. */
fun wholeNumber(s: String): Int? = s.trim().takeIf { WHOLE.matches(it) }?.toIntOrNull()

private val WHOLE = Regex("[0-9]+")

fun wholeNumberError(s: String, min: Int = 0): String? = when {
    otherDigits(s) -> USE_ASCII_DIGITS
    (wholeNumber(s) ?: Int.MIN_VALUE) >= min -> null
    else -> "A whole number, $min or more"
}

/** A plain decimal in 0-9 digits, with either separator; null when it isn't one. No signs, exponents, NaN or Infinity. */
fun decimal(s: String): Double? =
    s.trim().takeIf { DECIMAL.matches(it) }?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

private val DECIMAL = Regex("[0-9]+([.,][0-9]*)?|[.,][0-9]+")

/** The largest speed limit taken, 2 TB/s: far beyond any link, and still exact as KiB/s in a Long. */
const val MAX_LIMIT_BYTES = 2e12

/** An amount in [unit]; [emptyOk] when empty means none, as for speed limits. */
fun amountError(s: String, unit: String, emptyOk: Boolean = false): String? {
    if (emptyOk && s.isBlank()) return null
    if (otherDigits(s)) return USE_ASCII_DIGITS
    val v = decimal(s) ?: return if (s.trim().startsWith("-")) "0 or more" else "Enter a number"
    return when {
        unit == "%" && v > 100 -> "Up to ${withUnit(100, "%")}"
        unit == "kB/s" && v * 1e3 > MAX_LIMIT_BYTES -> "Up to ${withUnit(number(MAX_LIMIT_BYTES / 1e3), "kB/s")}"
        unit == "MB/s" && v * 1e6 > MAX_LIMIT_BYTES -> "Up to ${withUnit(number(MAX_LIMIT_BYTES / 1e6), "MB/s")}"
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
@Suppress("SdCardPath") // recognises the aliases a user may type; it doesn't build paths from them
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
 * O, I and B, and the check character ending every 14 must match. The core
 * also takes the 52-character form without check characters (Syncthing before
 * 2014); refused here, as 4 characters dropped while typing would make one.
 */
fun deviceId(s: String): String? {
    val id = idCharacters(s)
    if (id.length != 56 || id.any { it !in BASE32 }) return null
    val groups = id.chunked(14)
    if (groups.any { luhn32(it.dropLast(1)) != it.last() }) return null
    val data = groups.joinToString("") { it.dropLast(1) }
    // Decoded and encoded again, as the core does: the last character's spare bits drop.
    val canonical = base32(unbase32(data))
    return (0 until 4).joinToString("") { g -> canonical.substring(g * 13, g * 13 + 13).let { it + luhn32(it) } }.chunked(7).joinToString("-")
}

private fun idCharacters(s: String): String =
    s.trim().trim('=').uppercase().replace('0', 'O').replace('1', 'I').replace('8', 'B').replace("-", "").replace(" ", "")

/** Why [s] is not a device ID, or null when it is one. */
fun deviceIdError(s: String): String? = when {
    deviceId(s) != null -> null
    idCharacters(s).length != 56 -> "A device ID has 56 characters"
    else -> "Not a device ID. Check every character against the other device."
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
 * that isn't in its canonical form ("tcp://" with nothing after it isn't).
 * TCP and QUIC take 22000 when the port is left out (lib/connections/util.go
 * fixupPort; 0 picks one) and all interfaces when the host is.
 */
fun isListenAddress(s: String): Boolean {
    val a = hostPort(s) ?: return false
    return when (a.scheme) {
        "tcp", "tcp4", "tcp6", "quic", "quic4", "quic6" -> (a.host.isNotEmpty() || "://:" in s) && (a.rest.isEmpty() || a.rest == "/")
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

// Proxy fields: the bridge refuses the same (go/bridge/proxy.go proxyFrom), and
// go/bridge/testdata/validation-contract.json pins both to the same cases.

/** An IPv4 address as Go's net.ParseIP takes it: four 0-255 fields, no leading zeros. */
private fun isIPv4(s: String): Boolean {
    val parts = s.split('.')
    return parts.size == 4 && parts.all { p ->
        p.length in 1..3 && p.all { it in '0'..'9' } && (p == "0" || p[0] != '0') && p.toInt() <= 255
    }
}

/** An IPv6 address as Go's net.ParseIP takes it: no brackets, no zone, IPv4 only in the last 32 bits. */
private fun isIPv6(s: String): Boolean {
    val halves = s.split("::")
    if (':' !in s || halves.size > 2) return false
    // The 16-bit groups in one side of "::", or null when one is not hex.
    fun groups(part: String, last: Boolean): Int? {
        if (part.isEmpty()) return 0
        val g = part.split(':')
        return g.withIndex().sumOf { (i, x) ->
            when {
                last && i == g.lastIndex && '.' in x -> if (isIPv4(x)) 2 else return null
                x.length in 1..4 && x.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } -> 1
                else -> return null
            }
        }
    }
    if (halves.size == 1) return groups(s, last = true) == 8
    val n = (groups(halves[0], last = false) ?: return false) + (groups(halves[1], last = true) ?: return false)
    return n <= 7
}

/** A DNS name (RFC 1123 labels, ASCII); a last label of digits only is a mistyped IPv4 address. */
private fun isHostName(s: String): Boolean {
    if (s.isEmpty() || s.length > 253) return false
    val labels = s.removeSuffix(".").split('.')
    return labels.last().any { it !in '0'..'9' } && labels.all { l ->
        l.length in 1..63 && l.first() != '-' && l.last() != '-' && l.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
    }
}

fun proxyHostError(host: String): String? = when {
    host.isEmpty() -> "Required"
    host.startsWith("[") -> "Without brackets, for example ::1"
    isIPv4(host) || isIPv6(host) || isHostName(host) -> null
    else -> "An IP address or a host name"
}

fun proxyPortError(s: String): String? = when {
    otherDigits(s) -> USE_ASCII_DIGITS
    s.isBlank() -> "Required"
    (wholeNumber(s) ?: 0) in 1..65535 -> null
    else -> "A port from 1 to 65535"
}

/** UTF-16 that Go receives unchanged: a lone surrogate would arrive as U+FFFD. */
private fun String.validUnicode(): Boolean = indices.all { i ->
    val c = this[i]
    when {
        c.isHighSurrogate() -> i + 1 < length && this[i + 1].isLowSurrogate()
        c.isLowSurrogate() -> i > 0 && this[i - 1].isHighSurrogate()
        else -> true
    }
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

/** User name or password for the proxy; SOCKS5 (RFC 1929) carries at most 255 bytes each. */
fun proxyCredentialError(s: String): String? = when {
    !s.validUnicode() || s.any { it.isISOControl() } -> "No line breaks or control characters"
    s.utf8Size() > 255 -> "Too long: at most 255 bytes"
    else -> null
}

fun proxyUserError(user: String, password: String): String? =
    proxyCredentialError(user) ?: if (user.isEmpty() && password.isNotEmpty()) "Needed with a password" else null

/** All proxy fields as the bridge gets them; null when it takes them. [scheme] "" is no proxy. */
fun proxyError(scheme: String, host: String, port: Int, user: String, password: String): String? = when (scheme) {
    "" -> null
    "socks5", "http" -> proxyHostError(host)
        ?: (if (port in 1..65535) null else "A port from 1 to 65535")
        ?: proxyUserError(user, password) ?: proxyCredentialError(password)
    else -> "Unknown proxy type"
}

/** The shortest export password: WinZip AES stretches it with only 1000 PBKDF2 rounds. */
const val MIN_EXPORT_PASSWORD = 12

/** Why [password] can't protect an export (go/bridge ExportPasswordError); empty means none. Used as typed. */
fun exportPasswordError(password: String): String? = when {
    password.isEmpty() -> null
    !password.validUnicode() || password.any { it.isISOControl() } -> "No line breaks or control characters"
    password.codePointCount(0, password.length) < MIN_EXPORT_PASSWORD -> "At least $MIN_EXPORT_PASSWORD characters"
    password.utf8Size() > 1024 -> "Too long"
    else -> null
}

fun exportRepeatError(password: String, repeat: String): String? = if (password == repeat) null else "Not the same password"
