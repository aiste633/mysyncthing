package eu.stedb.mysyncthing.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Property tests for every form check: whatever a check lets through must be
 * something the core takes and uses as the user meant. Inputs are what people
 * type, paste or scan, plus junk. Fixed vectors come from the core's own code
 * (Syncthing v2.1.7), so the Kotlin ports are held to it.
 */
class ValidationTest {
    // --- Numbers

    @Test
    fun acceptedAmountsAreFiniteAndInRange() = forAll(typedNumber) { s ->
        for (unit in listOf("%", "MB", "GB", "kB/s", "MB/s")) {
            if (amountError(s, unit) != null) continue
            val v = decimal(s)!!
            assertTrue("$v finite, not negative", v.isFinite() && v >= 0)
            if (unit == "%") assertTrue(v <= 100)
        }
    }

    @Test
    fun speedLimitsSurviveTheCoresKiB() = forAll(typedNumber) { s ->
        for (unit in listOf("kB/s", "MB/s")) {
            if (amountError(s, unit, emptyOk = true) != null) continue
            val v = decimal(s) ?: 0.0
            val kib = limitKib(v, unit)
            val bytes = v * if (unit == "MB/s") 1e6 else 1e3
            if (v == 0.0) assertEquals(0L, kib) else {
                assertTrue("$kib KiB/s for $v $unit", kib >= 1)
                // Rounded to the nearest KiB, except that the smallest limit is 1 KiB/s, never "none".
                assertTrue("$kib KiB/s for $v $unit", abs(kib * 1024.0 - bytes) <= 512 || (kib == 1L && bytes < 1024))
            }
        }
    }

    @Test
    fun overflowingLimitsWereSavedAsOneKiB() {
        // 3,000,000 MB/s passed the app's Int as KiB and wrapped to a negative number, then became 1 KiB/s.
        assertEquals("Up to 2000000 MB/s", amountError("3000000", "MB/s"))
        assertEquals(1_953_125_000L, limitKib(2e6, "MB/s"))
        assertEquals(0L, limitKib(Double.NaN, "kB/s"))
        assertEquals("Enter a number", amountError("NaN", "%"))
        assertEquals("Enter a number", amountError("Infinity", "GB"))
        assertEquals("Enter a number", amountError("1e3", "GB"))
        assertEquals("0 or more", amountError("-1", "MB/s", emptyOk = true))
        assertEquals(null, amountError("2,5", "%"))
    }

    @Test
    fun wholeNumbersAreStoredAsTheCoreReadsThem() = forAll(typedNumber) { s ->
        for (type in listOf("trashcan", "simple", "staggered")) {
            val p = versioningParam(type)!!
            if (wholeNumberError(s, p.min) != null) continue
            val n = wholeNumber(s)!!
            assertTrue(n >= p.min)
            val stored = versioningStored(type, s)
            // Go's strconv.Atoi reads only ASCII digits, without a sign: other digits never get this far.
            assertTrue("stored $stored", stored.all { it in '0'..'9' } && stored.isNotEmpty())
            assertEquals(n.toLong() * (if (p.key == "maxAge") 86_400 else 1), stored.toLong())
            assertEquals(n.toString(), versioningShown(type, stored))
        }
    }

    @Test
    fun versionsToKeepIsAtLeastOne() {
        // The simple versioner removes every version beyond "keep", so 0 kept none.
        assertEquals("A whole number, 1 or more", wholeNumberError("0", versioningParam("simple")!!.min))
        assertEquals(null, wholeNumberError("0", versioningParam("trashcan")!!.min))
        // Persian digits were stored as typed; Go then read them as an error and used its default.
        // Refused, with the reason: converting them silently would surprise.
        assertEquals("Use the digits 0-9", wholeNumberError("۳۰"))
        assertEquals("A whole number, 0 or more", wholeNumberError("1.5"))
        assertEquals("A whole number, 0 or more", wholeNumberError("99999999999"))
    }

    // --- Device IDs

    /** An ID's 52 data characters, valid to the last spare bits, and its check characters as lib/protocol/luhn.go makes them. */
    private val validId: Gen<String> = {
        val b32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val data = chars(b32, 51, 51) + "AQ".random(this) // the 52nd character carries 1 bit
        data.chunked(13).joinToString("") { it + luhn(it) }.chunked(7).joinToString("-")
    }

    private fun luhn(s: String): Char {
        val b32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var factor = 1
        var sum = 0
        for (c in s) {
            val addend = factor * b32.indexOf(c)
            factor = 3 - factor
            sum += addend / 32 + addend % 32
        }
        return b32[(32 - sum % 32) % 32]
    }

    @Test
    fun deviceIdsMatchTheCore() {
        // Made by the core from fixed bytes (protocol.DeviceID.String), dashes left out for the leak check.
        for (id in listOf(
            "AAFRMIJMG5BE2XWDDNZ4YJD42UWWYLXRWR3TT7F7I7ICMPCSNB7JJKQW",
            "EUYDWRSRLRTXET7MISOPKTNF7ZLLK6B27WAEGBOIRYNHBBU4WLEN55AT",
            "JJKWA23WQGGJPOIVNXDB45WPE57D5AKEA3EYYTYR2RSLVUHG7UJSSPQR",
            "N55ILEE3U2Y3ZMR6S3XUPH7QJCQPPSUNKAJNLGC3D6XQKGZRI5OXHCAL",
        )) {
            assertEquals(id, deviceId(id)?.replace("-", ""))
            assertEquals(id, deviceId(id.lowercase().chunked(7).joinToString(" "))?.replace("-", ""))
        }
        // 8 groups of 7, as the old check wanted, but the core refuses them: "check digit incorrect".
        assertNull(deviceId("MFZWI3DBONSGYCYLTMRWGC43ENR5QXGZDMMFZWI3DPNSGYCYLTMRWGCA"))
        assertNull(deviceId("MFZWI3DBONSGYCYLTMRWGC43ENR5QXGZDMMFZWI3DPNSGYCYLTMRWGCB"))
        assertNull(deviceId("MFZWI3DONSGYCYLTMRWGC43ENR5QXGZDMMFZWI3DPNSGYCYLTMRWGC")) // 54: incorrect length
        assertEquals("A device ID has 56 characters", deviceIdError("AAFRMIJMG5BE2XWDDNZ4YJD42UWWYLXRWR3TT7F7I7ICMPCSNB7J"))
    }

    @Test
    fun droppedCharactersAreCaught() = forAll(validId, runs = 3_000) { id ->
        // Four dropped characters leave 52, the core's pre-2014 form without check characters:
        // that would be a different device that never connects.
        val r = Random(id.hashCode())
        var short = id.replace("-", "")
        repeat(4) {
            val i = r.nextInt(short.length)
            short = short.removeRange(i, i + 1)
            assertNull(short, deviceId(short))
            assertEquals("A device ID has 56 characters", deviceIdError(short))
        }
    }

    @Test
    fun everyWayOfWritingAnIdIsTheSameDevice() = forAll(validId) { id ->
        assertEquals(id, deviceId(id))
        val bare = id.replace("-", "")
        for (variant in listOf(
            bare, id.lowercase(), id.replace('-', ' '), " $id\n", "$id==",
            // The core reads 0, 1 and 8 as O, I and B.
            id.replace('O', '0').replace('I', '1').replace('B', '8'),
        )) assertEquals(variant, id, deviceId(variant))
    }

    @Test
    fun anyOneWrongCharacterIsCaught() = forAll(validId, runs = 3_000) { id ->
        val b32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val r = Random(id.hashCode())
        val positions = id.indices.filter { id[it] != '-' }
        repeat(8) {
            val i = positions.random(r)
            val c = b32.filter { it != id[i] }.random(r)
            val typo = id.substring(0, i) + c + id.substring(i + 1)
            assertNull(typo, deviceId(typo))
        }
    }

    @Test
    fun randomTextIsNoDeviceId() = forAll(oneOf(anyText, { chars("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567-", 50, 70) })) { s ->
        val id = deviceId(s) ?: return@forAll
        // The 4 check characters make a random hit about 1 in a million; then it is at least well formed.
        assertTrue(Regex("([A-Z2-7]{7}-){7}[A-Z2-7]{7}").matches(id))
        assertEquals(id, deviceId(id))
    }

    // --- Addresses

    private val address: Gen<String> = {
        val scheme = pick("tcp", "tcp4", "tcp6", "quic", "quic6", "relay", "dynamic+https", "udp", "http", "TCP", "")(this)
        val host = pick(
            "192.0.2.10", "198.51.100.7", "[2001:db8::1]", "2001:db8::1", "[::]", "0.0.0.0", "example.com", "relay.example.org",
            "my_box.example.net", "", "exa mple.com", "example.com.", "-", "[2001:db8::1", "[2001:db8::1%25wlan0]",
        )(this)
        val port = pick("", ":", ":0", ":22000", ":443", ":65535", ":65536", ":-1", ":abc", ":99999999", ":2200 0")(this)
        val tail = pick("", "", "", "/", "/x", "?id=x", "/?id=AAFRMIJMG5BE2XWDDNZ4YJD42UWWYLXRWR3TT7F7I7ICMPCSNB7JJKQW", "/?insecure", "#x", " ")(this)
        pick("", " ", "\t")(this) + (if (scheme.isEmpty()) "" else "$scheme://") + host + port + tail
    }

    private data class Parts(val scheme: String, val host: String, val port: Int?, val tail: String)

    /** An independent reading with java.net.URI; underscores make it fall back to the raw authority. */
    private fun parts(s: String): Parts {
        val u = java.net.URI(s)
        val authority = u.rawAuthority
        val host = (u.host ?: authority.substringBeforeLast(':')).removeSurrounding("[", "]")
        val port = u.port.takeIf { it >= 0 } ?: authority.substringAfterLast(':', "").takeIf { ']' !in it && it.isNotEmpty() }?.toInt()
        return Parts(u.scheme, host, port, (u.rawPath.orEmpty()) + (u.rawQuery?.let { "?$it" } ?: ""))
    }

    @Test
    fun acceptedDeviceAddressesCanBeDialled() = forAll(oneOf(address, { normalizeAddress(address()) }, anyText)) { s ->
        val a = normalizeAddress(s)
        if (!isDeviceAddress(a)) return@forAll
        val p = parts(a)
        assertTrue(p.scheme in setOf("tcp", "tcp4", "tcp6", "quic", "quic4", "quic6", "relay"))
        assertTrue("host", p.host.isNotEmpty())
        assertTrue("port ${p.port}", p.port == null || p.port in 1..65535)
        if (p.scheme != "relay") assertTrue("tail ${p.tail}", p.tail.isEmpty() || p.tail == "/")
    }

    @Test
    fun acceptedListenAddressesCanBeListenedOn() = forAll(oneOf(address, anyText)) { s ->
        if (!isListenAddress(s)) return@forAll
        val p = parts(s)
        assertTrue(p.scheme in setOf("tcp", "tcp4", "tcp6", "quic", "quic4", "quic6", "relay", "dynamic+http", "dynamic+https"))
        // TCP and QUIC: a port the core can bind, or none (22000); "scheme://" alone isn't canonical.
        if (p.scheme.startsWith("tcp") || p.scheme.startsWith("quic")) assertTrue("port", (p.port == null || p.port in 0..65535) && s != p.scheme + "://")
        else assertTrue("host", p.host.isNotEmpty())
        assertEquals("canonical: no spaces", s.trim(), s)
    }

    @Test
    fun acceptedDiscoveryServersAreHttpsWithAValidId() = forAll(oneOf({ address().replaceBefore("://", "https") }, address, anyText)) { s ->
        if (!isDiscoveryServer(s)) return@forAll
        val p = parts(s.trim())
        assertEquals("https", p.scheme)
        assertTrue(p.host.isNotEmpty() && (p.port == null || p.port in 1..65535))
        Regex("[?&]id=([^&]*)").find(p.tail)?.let { assertNotNull(deviceId(it.groupValues[1])) }
    }

    @Test
    fun addressExamples() {
        val https = "https" + "://"
        val tcp = "tcp" + "://"
        assertTrue(isDeviceAddress(normalizeAddress("192.0.2.10:22000")))
        assertTrue(isDeviceAddress("$tcp[2001:db8::1]:22000"))
        assertTrue(isDeviceAddress("quic://example.com"))
        assertFalse(isDeviceAddress("tcp://example.com:99999")) // the old check took any non-space text
        assertFalse(isDeviceAddress("tcp://example.com:abc"))
        assertFalse(isDeviceAddress("tcp://"))
        assertTrue(isListenAddress("tcp://0.0.0.0:22001"))
        assertTrue(isListenAddress("quic" + "://:22001"))
        assertTrue(isListenAddress("tcp://0.0.0.0")) // the core listens on 22000 (fixupPort)
        assertTrue(isListenAddress("quic" + "://:"))
        assertFalse(isListenAddress("tcp://")) // not canonical: Go writes it as "tcp:", and the core skips it
        assertFalse(isListenAddress("tcp://0.0.0.0:99999"))
        assertFalse(isListenAddress("TCP://0.0.0.0:22001")) // not canonical: the core skips it
        assertTrue(isDiscoveryServer("${https}discovery.example.com/"))
        assertTrue(isDiscoveryServer("${https}example.com:8443/?id=AAFRMIJMG5BE2XWDDNZ4YJD42UWWYLXRWR3TT7F7I7ICMPCSNB7JJKQW"))
        assertFalse(isDiscoveryServer("${https}example.com:8443/?id=ABC")) // the server's ID can't be checked
        assertTrue(isDiscoveryServer("${https}192.0.2.10:8443/?insecure"))
    }

    // --- Folders

    private val existing = listOf("/storage/emulated/0/DCIM", "/storage/1234-ABCD/Music/")

    private val path: Gen<String> = {
        val root = pick("/storage/emulated/0", "/storage/1234-ABCD", "/storage", "/sdcard", "/data/user/0/eu.stedb.mysyncthing/files", "", "~", "/")(this)
        val parts = (0 until nextInt(0, 4)).map { pick("DCIM", "Music", "Camera", "my photos", ".", "..", "", "Ünï 😀", "a\nb", "...", "Android")(this) }
        root + parts.joinToString("") { "/$it" } + pick("", "/", " ")(this)
    }

    @Test
    fun acceptedFolderPathsAreInSharedStorageAndApart() = forAll(oneOf(path, anyText)) { s ->
        if (folderPathError(s, existing) != null) return@forAll
        val p = folderPath(s)
        assertTrue(p.startsWith("/storage/"))
        val parts = p.split('/').drop(1)
        assertTrue("normalised", parts.none { it.isEmpty() || it == "." || it == ".." })
        assertTrue("no control characters", p.none { it.isISOControl() })
        assertEquals("normalised", java.io.File(p).normalize().path, p)
        for (e in existing.map { it.trimEnd('/') }) {
            assertFalse("overlaps $e", p == e || p.startsWith("$e/") || e.startsWith("$p/"))
        }
    }

    @Test
    fun folderPathExamples() {
        assertEquals(null, folderPathError("/storage/emulated/0/Documents/", existing))
        assertEquals("Another folder is already here, or inside it", folderPathError("/storage/emulated/0/DCIM/Camera", existing))
        assertEquals("Another folder is already here, or inside it", folderPathError("/storage/1234-ABCD", existing))
        assertEquals("In the phone's storage or on an SD card (/storage/…)", folderPathError("/data/user/0/eu.stedb.mysyncthing/files/syncthing"))
        assertEquals("No empty, . or .. parts", folderPathError("/storage/emulated/0/Syncthing/.."))
        assertEquals("No line breaks or control characters", folderPathError("/storage/emulated/0/a\nb"))
        assertEquals("/storage/emulated/0/Music", folderPath(" /sdcard/Music/ "))
        assertEquals("Another folder is already here, or inside it", folderPathError("/sdcard/DCIM", existing))
    }

    @Test
    fun acceptedFolderIdsFitInOneUrlSegment() = forAll(oneOf(anyText, pick(".", "..", "a/b", " x ", "abcde-12345", "My photos"))) { s ->
        if (folderIdError(s, listOf("taken")) != null) return@forAll
        val t = s.trim()
        assertTrue(t.isNotEmpty() && '/' !in t && t != "." && t != ".." && t != "taken")
    }

    // --- Folder names offered by other devices

    @Test
    fun suggestedPathsStayInTheBase() = forAll(oneOf(anyText, pick(".", "..", " .. ", "a/../b", "CON", "x".repeat(300), "😀".repeat(100)))) { label ->
        val base = "/storage/emulated/0/Syncthing"
        val p = suggestedPath(label, base, "abcde-12345")
        val name = p.removePrefix("$base/")
        assertTrue(p.startsWith("$base/"))
        assertTrue("one name: $name", name.isNotEmpty() && '/' !in name && name != "." && name != "..")
        assertTrue("no control characters", name.none { it.isISOControl() } && name == name.trim())
        assertTrue("fits a file name", name.toByteArray().size <= 255)
        assertEquals("$base/$name", java.io.File(p).normalize().path)
        assertEquals(name, safeName(name)) // stable when shown and edited again
    }

    @Test
    fun namesAreSanitisedLikeTheCore() {
        // lib/fs/util.go SanitizePath, run on these inputs (v2.1.7).
        for ((input, core) in listOf(
            "Photos" to "Photos", "  My  Photos " to "My Photos", "a/b" to "a b", "a\\b" to "a b", "CON" to "-CON",
            "con.txt" to "-con.txt", "nul" to "-nul", "Docs: 2026?" to "Docs 2026", "x\u0000y\tz" to "x y z",
            "Ünïcødé 😀" to "Ünïcødé 😀", "[draft] {v2}; #1 @home \$5 50% & ^" to "draft v2 1 home 5 50", "​" to "",
            "a b" to "a b", "COM1.jpg" to "-COM1.jpg", "LPT9" to "-LPT9", "AUX" to "-AUX", "..." to "...",
            "�" to "", "é" to "é",
        )) assertEquals(input, core.ifEmpty { null }, safeName(input))
        // The core keeps "." and "..", which name the folder above: a device offering ".." got the whole storage suggested.
        assertNull(safeName(".."))
        assertNull(safeName(" . "))
        assertEquals("/storage/emulated/0/Syncthing/abcde-12345", suggestedPath("..", "/storage/emulated/0/Syncthing", "abcde-12345"))
        assertEquals("/storage/emulated/0/Syncthing/Folder", suggestedPath("..", "/storage/emulated/0/Syncthing", ".."))
    }
}
