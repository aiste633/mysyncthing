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
}
