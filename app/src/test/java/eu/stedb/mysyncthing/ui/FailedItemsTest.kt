package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.FileError
import eu.stedb.mysyncthing.core.problemText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedItemsTest {
    private val keepFree = FileError(
        "photos/a.jpg",
        "syncing: insufficient space in folder \"Test\" (test) (/storage/emulated/0/test): current 61.19 % < required 99 %",
    )
    private val other = FileError("docs/b.txt", "permission denied")

    /** The rows show plain words, but Copy all gives the core's lines, as a bug report needs them. */
    @Test
    fun copyAllKeepsTheCoresText() {
        val copied = failedItemsCopy(listOf(keepFree, other))
        assertEquals("${keepFree.path}: ${keepFree.error}\n${other.path}: ${other.error}", copied)
        assertFalse(copied.contains(problemText(keepFree.error).en()))
    }

    @Test
    fun searchFindsShownAndCoreText() {
        assertTrue(failedItemMatches(keepFree, "free space", En)) // shown text only
        assertTrue(failedItemMatches(keepFree, "Insufficient", En)) // core's text only
        assertTrue(failedItemMatches(keepFree, " photos/ ", En))
        assertFalse(failedItemMatches(other, "free space", En))
        assertTrue(failedItemMatches(other, "", En))
    }
}
