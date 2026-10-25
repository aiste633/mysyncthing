package eu.stedb.mysyncthing.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun siBytes() {
        assertEquals("999 B", bytes(999))
        assertEquals("1.0 kB", bytes(1000))
        assertEquals("1.5 MB", bytes(1_500_000))
        assertEquals("2.0 GB", bytes(2_000_000_000))
    }

    @Test
    fun shortIdIsFirstGroup() {
        assertEquals("ABCDEFG", shortId("ABCDEFG-HIJKLMN-OPQRSTU"))
    }

    @Test
    fun internalStorageIsRelative() {
        assertEquals("/DCIM/Camera", displayPath("/storage/emulated/0/DCIM/Camera"))
        assertEquals("/storage/1234-ABCD/Music", displayPath("/storage/1234-ABCD/Music"))
    }

    @Test
    fun staggeredEditsDaysButStoresSeconds() {
        assertEquals("31536000", versioningStored("staggered", "365"))
        assertEquals("365", versioningShown("staggered", "31536000"))
        assertEquals("Staggered · 365 days", versioningSummary("staggered", mapOf("maxAge" to "31536000")))
        assertEquals("Trash can · keep forever", versioningSummary("trashcan", emptyMap()))
        assertEquals("Simple · 5 versions", versioningSummary("simple", emptyMap()))
    }

    @Test
    fun ratesAndDurationsCarryUnits() {
        assertEquals("1.2 MB/s", rate(1_200_000))
        assertEquals("45 s", duration(45))
        assertEquals("12 min", duration(720))
        assertEquals("3 h 5 min", duration(3 * 3600 + 300))
        assertEquals("2 d 4 h", duration(2 * 86_400 + 4 * 3600 + 59))
    }

    @Test
    fun timesAreDayFirstAnd24h() {
        val now = java.time.ZonedDateTime.parse("2026-10-25T14:00:00Z")
        val en = java.util.Locale.ENGLISH
        assertEquals("12:03", whenText("2026-10-25T12:03:41.5Z", now, en))
        assertEquals("yesterday, 22:10", whenText("2026-10-24T22:10:00Z", now, en))
        assertEquals("3 Oct, 08:05", whenText("2026-10-03T08:05:00Z", now, en))
        assertEquals("3 Oct 2025, 18:42", whenText("2025-10-03T18:42:00+00:00", now, en))
        assertEquals(null, whenText("0001-01-01T00:00:00Z", now, en))
        assertEquals(null, whenText("garbage", now, en))
    }
}
