package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ConflictsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val copy = "budget.sync-conflict-20261105-142007-LPT7Q2X.ods"

    @Test
    fun upstreamsTestLooksAtTheBaseNameOnly() {
        assertTrue(isConflictCopy(copy))
        assertTrue(isConflictCopy("work/2026/$copy"))
        assertTrue(isConflictCopy("Makefile.sync-conflict-20261105-142007-LPT7Q2X")) // no extension
        assertFalse(isConflictCopy("budget.ods"))
        assertFalse(isConflictCopy("old.sync-conflict-20261105-142007-LPT7Q2X/budget.ods")) // a directory's name
        assertFalse(isConflictCopy("budget-sync-conflict.ods"))
    }

    private fun file(path: String) = File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText("x") }

    @Test
    fun theWalkFindsCopiesAtAnyDepthAndSkipsTheCoresOwnDirectories() {
        file(copy)
        file("work/2026/$copy")
        file("work/budget.ods")
        file(".stversions/budget.sync-conflict-20261105-142007-LPT7Q2X~20261106-090000.ods") // an old version of a copy
        file(".stfolder/$copy")
        file("deep/.stversions/$copy") // only the root's are the core's
        File(tmp.root, "dir.sync-conflict-20261105-142007-LPT7Q2X").mkdirs() // directories are never copies
        assertEquals(listOf(copy, "deep/.stversions/$copy", "work/2026/$copy"), findConflictCopies(tmp.root))
    }

    @Test
    fun theWalkFollowsNoLinks() {
        val outside = tmp.newFolder("outside").apply { File(this, copy).writeText("x") }
        val root = tmp.newFolder("root")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "file-link.sync-conflict-20261105-142007-LPT7Q2X.ods").toPath(), File(outside, copy).toPath())
        assertEquals(emptyList<String>(), findConflictCopies(root))
    }

    @Test
    fun aMissingFolderHasNone() {
        assertEquals(emptyList<String>(), findConflictCopies(File(tmp.root, "gone")))
    }

    /** A tracker over a fake disk: [disk] holds the files that exist, as "path/name". */
    private class Fake {
        val disk = mutableSetOf<String>()
        val walks = mutableListOf<String>()
        val tracker = ConflictTracker(
            walk = { root -> walks += root.path; disk.filter { it.startsWith(root.path + "/") }.map { it.removePrefix(root.path + "/") }.filter(::isConflictCopy) },
            exists = { it.path in disk },
        )
    }

    private val docs = Folder("docs", path = "/sd/Docs")
    private val music = Folder("music", path = "/sd/Music")

    @Test
    fun eachFolderIsWalkedOnceThenFollowsTheIndexUpdates() {
        val f = Fake()
        f.disk += "/sd/Docs/$copy"
        f.tracker.folders(listOf(docs, music))
        f.tracker.folders(listOf(docs, music))
        assertEquals(listOf("/sd/Docs", "/sd/Music"), f.walks)
        assertEquals(mapOf("docs" to listOf(copy)), f.tracker.current())

        // A new copy, then the first one deleted (here or on another device): the disk decides.
        val second = "notes.sync-conflict-20261103-091200-DSK4R2A.md"
        f.disk += "/sd/Docs/$second"
        f.tracker.changed("docs", listOf("notes.md", second))
        assertEquals(mapOf("docs" to listOf(copy, second)), f.tracker.current())
        f.disk -= "/sd/Docs/$copy"
        f.tracker.changed("docs", listOf(copy))
        assertEquals(mapOf("docs" to listOf(second)), f.tracker.current())

        // Updates without copies change nothing; a deleted copy that was never known is no change either.
        f.tracker.changed("docs", listOf("notes.md"))
        f.tracker.changed("music", listOf(copy))
        assertEquals(mapOf("docs" to listOf(second)), f.tracker.current())
    }

    @Test
    fun anUpdateBeforeTheWalkIsLeftToTheWalk() {
        val f = Fake()
        f.disk += "/sd/Docs/$copy"
        f.tracker.changed("docs", listOf(copy))
        assertEquals(emptyMap<String, List<String>>(), f.tracker.current())
        f.tracker.folders(listOf(docs))
        assertEquals(mapOf("docs" to listOf(copy)), f.tracker.current())
    }

    @Test
    fun eachRefreshDropsCopiesGoneWithoutAnUpdate() {
        // Hidden by an ignore pattern: the core never reports it, so only the refresh notices it went.
        val f = Fake()
        f.disk += "/sd/Docs/$copy"
        f.tracker.folders(listOf(docs))
        f.disk.clear()
        f.tracker.folders(listOf(docs))
        assertEquals(emptyMap<String, List<String>>(), f.tracker.current())
        assertEquals(1, f.walks.size)
    }

    @Test
    fun aMovedFolderIsWalkedAgainAndARemovedOneForgotten() {
        val f = Fake()
        f.disk += "/sd/Docs/$copy"
        f.disk += "/sd/Docs2/$copy"
        f.tracker.folders(listOf(docs, music))
        f.tracker.folders(listOf(docs.copy(path = "/sd/Docs2")))
        assertEquals(listOf("/sd/Docs", "/sd/Music", "/sd/Docs2"), f.walks)
        assertEquals(mapOf("docs" to listOf(copy)), f.tracker.current())
        f.tracker.folders(emptyList())
        assertEquals(emptyMap<String, List<String>>(), f.tracker.current())
        // Back again: walked again, not taken as known.
        f.tracker.folders(listOf(docs))
        assertEquals(4, f.walks.size)
    }

    @Test
    fun forgettingWalksEveryFolderAgain() {
        val f = Fake()
        f.tracker.folders(listOf(docs))
        f.tracker.forget()
        assertEquals(emptyMap<String, List<String>>(), f.tracker.current())
        f.tracker.folders(listOf(docs))
        assertEquals(2, f.walks.size)
    }

    @Test
    fun theSummaryCountsCopiesWhileTheCoreRunsWithoutChangingTheState() {
        val running = CoreState.Running(RestClient("127.0.0.1:1", "k"), "127.0.0.1:1", "ME", Credentials("k", "u", "p"))
        val server = Device("SERVER1-X", name = "server")
        val s = Snapshot(
            loaded = true, myId = "ME", devices = listOf(server), folders = listOf(docs),
            folderStatus = mapOf("docs" to FolderStatus(state = "idle")),
            connections = mapOf(server.deviceID to Connection(connected = true)),
            conflicts = mapOf("docs" to listOf(copy, "a/$copy")),
        )
        val summary = summarize(running, s, null, true)
        assertEquals(SyncState.UpToDate, summary.state)
        assertEquals(2, summary.conflicts)
        assertEquals(summarize(running, s.copy(conflicts = emptyMap()), null, true), summary.copy(conflicts = 0))
        assertEquals(0, summarize(CoreState.Stopped, s, null, true).conflicts)
    }

    @Test
    fun aCopysNameTellsTheFileTimeAndDevice() {
        val at = java.time.LocalDateTime.of(2026, 11, 5, 14, 20, 7)
        assertEquals(ConflictCopy(copy, "budget.ods", at, "LPT7Q2X"), parseConflictCopy(copy))
        assertEquals(ConflictCopy("work/$copy", "work/budget.ods", at, "LPT7Q2X"), parseConflictCopy("work/$copy"))
        // The extension is only the last one: "archive.tar" + ".gz".
        assertEquals("archive.tar.gz", parseConflictCopy("archive.tar.sync-conflict-20261105-142007-LPT7Q2X.gz")?.original)
        assertEquals("Makefile", parseConflictCopy("Makefile.sync-conflict-20261105-142007-LPT7Q2X")?.original)
        // Before v0.14: no device.
        assertEquals(ConflictCopy("a.sync-conflict-20160101-120000.txt", "a.txt", java.time.LocalDateTime.of(2016, 1, 1, 12, 0), null), parseConflictCopy("a.sync-conflict-20160101-120000.txt"))
        // A copy of a copy is not made (the core deletes it), but a name may still look like one.
        assertEquals("a.sync-conflict-20261105-142007-LPT7Q2X.txt", parseConflictCopy("a.sync-conflict-20261105-142007-LPT7Q2X.sync-conflict-20261106-090000-DSK4R2A.txt")?.original)
        // An impossible date keeps the copy, without a time.
        assertEquals(null, parseConflictCopy("a.sync-conflict-20261399-250000-LPT7Q2X.txt")?.madeAt)
        assertEquals(null, parseConflictCopy("budget.ods"))
        assertEquals(null, parseConflictCopy("a.sync-conflict-2026.txt"))
    }
}
