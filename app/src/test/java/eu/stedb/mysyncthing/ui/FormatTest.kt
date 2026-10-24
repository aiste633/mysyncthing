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
}
