package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun restoredFilesGetTheRestoreTime() {
        val root = tmp.root
        root.resolve("dir").mkdir()
        val files = listOf("a.txt", "dir/b.txt").onEach { root.resolve(it).apply { writeText("x"); setLastModified(1_000_000_000_000) } }
        val now = 1_800_000_000_000
        assertEquals(emptyList<String>(), dateNow(root, files, now))
        files.forEach { assertEquals(now, root.resolve(it).lastModified()) }
    }

    @Test fun aFileThatCannotBeDatedIsReported() {
        assertEquals(listOf("gone.txt"), dateNow(tmp.root, listOf("gone.txt"), 1_800_000_000_000))
    }
}
