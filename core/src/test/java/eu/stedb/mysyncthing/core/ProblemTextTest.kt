package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProblemTextTest {
    @Test
    fun fullDiskInPlainWords() {
        assertEquals(
            "The phone's storage is almost full (Syncthing keeps 1 % free). Free some space: syncing goes on by itself.",
            problemText("insufficient space on disk for database (/data/user/0/eu.stedb.mysyncthing/files/syncthing/index-v2): current 0.00 % < required 1 %").en(),
        )
        assertEquals(
            "Too little free space where this folder is (Syncthing keeps 2 GB free). Free some space, or lower \"Keep free\" in the folder's settings.",
            problemText("insufficient space in folder \"Photos\" (photos) (basic:///storage/emulated/0/DCIM): current 1.2 GB < required 2 GB").en(),
        )
        assertEquals(
            "The phone's storage is full. Free some space, then try again.",
            problemText("database: openbase (PRAGMA journal_mode = WAL): disk I/O error: no space left on device").en(),
        )
        // A single file failing on the folder's "Keep free" (a pull error).
        assertEquals(
            "Too little free space where this folder is (Syncthing keeps 99\u00A0% free). Free some space, or lower \"Keep free\" in the folder's settings.",
            problemText("syncing: insufficient space in folder \"Test\" (test) (/storage/emulated/0/test): current 61.19 % < required 99 %").en(),
        )
        // Without a readable minimum the sentence goes without it.
        assertEquals("The phone's storage is almost full. Free some space: syncing goes on by itself.", problemText("insufficient space on disk for database (x): odd").en())
    }

    @Test
    fun otherTextsUnchanged() {
        for (raw in listOf("folder marker missing (this indicates potential data loss, search docs/forum to get information about how to proceed)", "", "permission denied"))
            assertEquals(raw, problemText(raw).en())
    }

    /** No database path or percentage reaches people, whatever the numbers. */
    @Test
    fun noInternalsShown() {
        val r = kotlin.random.Random(7)
        repeat(5_000) {
            val pct = "%.2f".format(java.util.Locale.ROOT, r.nextDouble(0.0, 100.0))
            val raw = "insufficient space on disk for database (/data/user/${r.nextInt(0, 99)}/x/index-v2): current $pct % < required ${r.nextInt(1, 50)} %"
            val text = problemText(raw).en()!!
            assertFalse(text, "/data" in text || pct in text || "database" in text)
        }
    }
}
