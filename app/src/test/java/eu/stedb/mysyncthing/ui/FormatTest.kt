package eu.stedb.mysyncthing.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test
    fun siBytes() {
        assertEquals("999\u00A0B", bytes(999))
        assertEquals("1.0\u00A0kB", bytes(1000))
        assertEquals("1.5\u00A0MB", bytes(1_500_000))
        assertEquals("2.0\u00A0GB", bytes(2_000_000_000))
    }

    @Test
    fun shortIdIsFirstGroup() {
        assertEquals("ABCDEFG", shortId("ABCDEFG-HIJKLMN-OPQRSTU"))
    }

    @Test
    fun internalStorageIsRelative() {
        assertEquals("/DCIM/Camera", displayPath("/storage/emulated/0/DCIM/Camera").en())
        assertEquals("/storage/1234-ABCD/Music", displayPath("/storage/1234-ABCD/Music").en())
    }

    @Test
    fun staggeredEditsDaysButStoresSeconds() {
        assertEquals("31536000", versioningStored("staggered", "365"))
        assertEquals("365", versioningShown("staggered", "31536000"))
        assertEquals("Staggered · 365 days", versioningSummary("staggered", mapOf("maxAge" to "31536000")).en())
        assertEquals("Trash can · keep forever", versioningSummary("trashcan", emptyMap()).en())
        assertEquals("Simple · 5 versions", versioningSummary("simple", emptyMap()).en())
    }

    @Test
    fun ratesAndDurationsCarryUnits() {
        assertEquals("1.2\u00A0MB/s", rate(1_200_000))
        assertEquals("45\u00A0s", duration(45))
        assertEquals("12\u00A0min", duration(720))
        assertEquals("3\u00A0h 5\u00A0min", duration(3 * 3600 + 300))
        assertEquals("2\u00A0d 4\u00A0h", duration(2 * 86_400 + 4 * 3600 + 59))
    }

    @Test
    fun timesAreDayFirstAnd24h() {
        val now = java.time.ZonedDateTime.parse("2026-10-25T14:00:00Z")
        val en = java.util.Locale.ENGLISH
        assertEquals("12:03", whenText("2026-10-25T12:03:41.5Z", now, en).en())
        assertEquals("yesterday, 22:10", whenText("2026-10-24T22:10:00Z", now, en).en())
        assertEquals("3 Oct, 08:05", whenText("2026-10-03T08:05:00Z", now, en).en())
        assertEquals("3 Oct 2025, 18:42", whenText("2025-10-03T18:42:00+00:00", now, en).en())
        assertEquals(null, whenText("0001-01-01T00:00:00Z", now, en).en())
        assertEquals(null, whenText("garbage", now, en).en())
    }

    /** A line can't end between a number and its unit: every unit follows a no-break space. */
    @Test
    fun unitsNeverWrapAlone() {
        val r = kotlin.random.Random(4)
        repeat(5_000) {
            val texts = listOf(
                bytes(r.nextLong(0, Long.MAX_VALUE) shr r.nextInt(63)), rate(r.nextLong(0, 1L shl 40)),
                duration(r.nextLong(0, 1L shl 30)), rescanText(r.nextInt(-10, 1 shl 20)).en(), limitText(r.nextLong(-5, 1L shl 40)).en(),
                exactTimeText(java.time.ZonedDateTime.now().minusSeconds(r.nextLong(-100, 7200)).toOffsetDateTime().toString()).en(),
            )
            texts.forEach { assertFalse(it, Regex("\\d [a-zA-Z%]").containsMatchIn(it)) }
        }
    }

    @Test
    fun fileDatesAreAbsolute() {
        val utc = java.time.ZoneOffset.UTC
        val en = java.util.Locale.ENGLISH
        // Never relative ("yesterday"): a file's date is compared with when the backup was made.
        assertEquals("26 Oct 2026, 19:27", dateTimeText(java.time.Instant.parse("2026-10-26T19:27:59Z").toEpochMilli(), utc, en))
        assertEquals("3 Jan 2025, 07:05", dateTimeText(java.time.Instant.parse("2025-01-03T07:05:00Z").toEpochMilli(), utc, en))
    }

    /** Only a leading "/" gets glued to the next character; removing the joiner gives the path back. */
    @Test
    fun wrapPathProperty() {
        val r = kotlin.random.Random(3)
        val pool = "/ab-._ é😀"
        repeat(5_000) {
            val p = buildString { repeat(r.nextInt(12)) { append(pool[r.nextInt(pool.length)]) } }
            val w = wrapPath(p)
            assertEquals(p, w.replace("\u2060", ""))
            assertEquals(p.length > 1 && p[0] == '/', w.startsWith("/\u2060"))
            assertTrue(w.count { it == '\u2060' } <= 1)
        }
        assertEquals("/", wrapPath("/"))
    }

    @Test
    fun folderSettingTexts() {
        assertEquals("1\u00A0%", diskSizeText(eu.stedb.mysyncthing.core.DiskSize()))
        assertEquals("2.5\u00A0GB", diskSizeText(eu.stedb.mysyncthing.core.DiskSize(2.5, "GB")))
        assertEquals("Every hour", rescanText(3600).en())
        assertEquals("Every 6\u00A0hours", rescanText(21_600).en())
        assertEquals("Once a day", rescanText(86_400).en())
        assertEquals("Never", rescanText(0).en())
        assertEquals(listOf(3_600, 21_600, 86_400, 0, 600), rescanChoices(600).map { it.first })
        assertEquals("Smallest first", orderLabel("smallestFirst").en())
    }

    @Test
    fun speedLimitsRoundTripInSi() {
        assertEquals("No limit", limitText(0).en())
        assertEquals(488, limitKib(500.0, "kB/s"))
        assertEquals("500\u00A0kB/s", limitText(488).en())
        assertEquals(977, limitKib(1.0, "MB/s"))
        assertEquals("1\u00A0MB/s", limitText(977).en())
        assertEquals("2.5\u00A0MB/s", limitText(limitKib(2.5, "MB/s")).en())
        assertEquals(1, limitKib(0.1, "kB/s"))
        assertEquals(0, limitKib(0.0, "MB/s"))
        assertEquals("Automatic", connectionsText(0).en())
        assertEquals(listOf(0, 1, 2, 3, 4, 8, 6), connectionChoices(6).map { it.first })
    }

    @Test
    fun discoveryServersNeedHttpsAndAHost() {
        val https = "https" + "://"
        assertTrue(isDiscoveryServer("${https}discovery.example.com/"))
        assertTrue(isDiscoveryServer("${https}192.0.2.10:8443/?insecure"))
        assertFalse(isDiscoveryServer("http" + "://example.com/"))
        assertFalse(isDiscoveryServer("${https}/path"))
        assertFalse(isDiscoveryServer("${https}example.com:99999/"))
        assertFalse(isDiscoveryServer("${https}user@example.com/"))
        assertFalse(isDiscoveryServer("${https}exa mple.com/"))
        assertFalse(isDiscoveryServer("discovery.example.com"))
    }

    @Test
    fun newFoldersFollowTheDefaultLocation() {
        assertEquals("/storage/emulated/0/Syncthing", pathBase(eu.stedb.mysyncthing.core.Folder(path = "~")))
        assertEquals("/storage/emulated/0/Sync", pathBase(eu.stedb.mysyncthing.core.Folder(path = "/storage/emulated/0/Sync/")))
        assertEquals("/storage/emulated/0/Sync/a b", suggestedPath(" a/b ", "/storage/emulated/0/Sync"))
    }

    @Test
    fun untrustedShareRows() {
        val untrusted = eu.stedb.mysyncthing.core.Device("D", untrusted = true)
        assertEquals(null, encryptionText(untrusted.copy(untrusted = false), null).en())
        assertEquals("Untrusted: needs a password to share", encryptionText(untrusted, null).en())
        assertEquals("Encrypted · password set", encryptionText(untrusted, eu.stedb.mysyncthing.core.FolderDevice("D", encryptionPassword = "x")).en())
        assertEquals("No password: Syncthing stops sharing it", encryptionText(untrusted, eu.stedb.mysyncthing.core.FolderDevice("D")).en())
    }

    @Test
    fun dayHeadingsAndClock() {
        val now = java.time.ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, java.time.ZoneOffset.UTC)
        assertEquals("Today", dayText("2026-10-25T08:00:00Z", now, java.util.Locale.UK).en())
        assertEquals("Yesterday", dayText("2026-10-24T23:59:00Z", now, java.util.Locale.UK).en())
        assertEquals("3 Oct", dayText("2026-10-03T10:00:00Z", now, java.util.Locale.UK).en())
        assertEquals("3 Oct 2025", dayText("2025-10-03T10:00:00Z", now, java.util.Locale.UK).en())
        assertEquals("18:02:12", exactTimeText("2026-10-24T18:02:12.123456789+00:00", now).en())
    }

    @Test
    fun exactTimes() {
        val now = java.time.ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, java.time.ZoneOffset.ofHours(1))
        assertEquals("11:59:30 · just now", exactTimeText("2026-10-25T10:59:30Z", now).en())
        assertEquals("11:55:00 · 5\u00A0min ago", exactTimeText("2026-10-25T11:55:00+01:00", now).en())
        assertEquals("11:00:01 · 59\u00A0min ago", exactTimeText("2026-10-25T11:00:01+01:00", now).en())
        assertEquals("11:00:00", exactTimeText("2026-10-25T11:00:00+01:00", now).en())
        assertEquals("12:00:05", exactTimeText("2026-10-25T12:00:05+01:00", now).en()) // ahead of the phone's clock
        assertEquals("", exactTimeText("not a time", now).en())
    }

    /** Every readable time shows its seconds in the phone's zone; how long ago only within the hour before now. */
    @Test
    fun exactTimesProperty() {
        val now = java.time.ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, java.time.ZoneId.of("Europe/Rome"))
        forAll({ nextLong(-7200, 200_000) to nextInt(-12, 13) }) { (secondsAgo, offset) ->
            val t = now.toInstant().minusSeconds(secondsAgo).atOffset(java.time.ZoneOffset.ofHours(offset))
            val text = exactTimeText(t.toString(), now).en()
            val clock = t.atZoneSameInstant(now.zone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
            assertTrue(text, text.startsWith(clock))
            assertEquals(text, secondsAgo in 0 until 3600, " · " in text)
        }
    }

    /** Whatever the core sends as a time: no crash, and either nothing or a 24-hour clock with ASCII digits. */
    @Test
    fun exactTimesOfAnyText() {
        val now = java.time.ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, java.time.ZoneId.of("Europe/Rome"))
        val clock = Regex("^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d( · .*)?$")
        forAll(oneOf(anyText, { "+999999999-12-31T23:59:59Z" }, { "-999999999-01-01T00:00:00+18:00" }, { now.minusSeconds(nextLong(-1, 4000)).toOffsetDateTime().toString() + chars(" x", 0, 2) })) { s ->
            val text = exactTimeText(s, now).en()
            assertTrue("$s -> $text", text.isEmpty() || clock.matches(text))
        }
    }

    @Test
    fun revertVerbsAgree() {
        assertEquals(
            "1 item changed on this phone is replaced with the version from your other devices, " +
                "or deleted if it doesn't exist there. This can't be undone.",
            revertText(1).en(),
        )
        assertEquals(true, revertText(2).en().startsWith("2 items changed on this phone are replaced"))
        assertEquals(true, revertText(2).en().contains("if they don't exist"))
    }

    @Test
    fun formChecksSayWhy() {
        assertEquals("Required", requiredError(" ").en())
        assertEquals(null, requiredError("a").en())
        assertEquals("A whole number, 0 or more", wholeNumberError("1.5").en())
        assertEquals(null, wholeNumberError(" 30 ").en())
        assertEquals("Up to 100\u00A0%", amountError("101", "%").en())
        assertEquals(null, amountError("101", "GB").en())
        assertEquals(null, amountError("2,5", "%").en())
        assertEquals("Enter a number", amountError("", "%").en())
        assertEquals(null, amountError("", "kB/s", emptyOk = true).en())
        assertEquals("0 or more", amountError("-1", "MB/s", emptyOk = true).en())
        assertEquals("Required", folderPathError("").en())
        assertEquals("A full path, starting with /", folderPathError("DCIM").en())
        assertEquals(null, folderPathError("/storage/emulated/0/DCIM").en())
        assertEquals("Another folder already has this ID", folderIdError("abcde-12345 ", listOf("abcde-12345")).en())
        assertEquals("Required", folderIdError("", emptyList()).en())
    }
}
