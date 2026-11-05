package eu.stedb.mysyncthing.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The folder browser's rules, without a screen: what each directory shows, what can be chosen, where it opens. */
class FolderBrowserTest {
    private val root = internalStorage
    private val disk = fixtureDisk()
    private val roots = disk.roots()
    private val folders = Fixtures.snapshot.folders // Photos at DCIM/Camera, Documents, Music

    private fun rows(dir: String, hidden: Boolean = false) = browseRows(dir, disk.directories(dir)!!, folders, hidden)

    @Test
    fun topOfInternalStorage() {
        val r = rows(root)
        assertEquals(listOf("Android", "DCIM", "Documents", "Download", "Music", "Pictures", "Syncthing"), r.map { it.name })
        assertFalse("Android/media holds other apps' media to sync", r.first { it.name == "Android" }.locked)
        assertEquals(1, r.first { it.name == "DCIM" }.syncedInside)
        assertEquals("Documents", r.first { it.name == "Documents" }.syncedAs)
        assertEquals(31, r.first { it.name == "Download" }.items)
        assertEquals(".thumbnails", rows(root, hidden = true).first().name)
    }

    @Test
    fun androidKeepsDataAndObb() {
        val r = rows("$root/Android").associateBy { it.name }
        assertTrue(r.getValue("data").locked)
        assertTrue(r.getValue("obb").locked)
        assertFalse(r.getValue("media").locked)
        assertEquals("Android keeps this folder for apps", pickProblem("$root/Android/data", folders).en())
    }

    @Test
    fun whatCanBeChosen() {
        assertNull(pickProblem("$root/Download", folders))
        assertNull(pickProblem("$root/Pictures", folders))
        assertEquals("Already synced as \"Documents\"", pickProblem("$root/Documents", folders).en())
        assertEquals("Inside the synced folder \"Photos\"", pickProblem("$root/DCIM/Camera/2026", folders).en())
        assertEquals("Holds the synced folder \"Photos\"", pickProblem("$root/DCIM", folders).en())
        assertEquals("Holds the synced folder \"Photos\"", pickProblem(root, folders).en())
        assertNull(pickProblem("/storage/1234-ABCD/Films", folders))
        // The top of a storage and its Android folder hold Android/data and obb, which the core can't read.
        val holds = "Holds folders Android keeps for apps (Android/data, obb)"
        assertEquals(holds, pickProblem("/storage/1234-ABCD", folders).en())
        assertEquals(holds, pickProblem("$root/Android", folders).en())
        assertNull(pickProblem("$root/Android/media", folders))
        assertEquals("Holds the synced folder \"Photos\"", pickProblem("/sdcard/DCIM/", folders).en())
    }

    /** Whatever the browser lets through, the form takes too. */
    @Test
    fun aChosenPlaceIsAValidFolder() = forAll({ pick(*disk.dirs.keys.toTypedArray(), "/storage", "/storage/emulated", "/data/user/0")(this) }) { p ->
        if (pickProblem(p, folders) == null) assertNull(folderPathError(p, folders.map { it.path }))
    }

    @Test
    fun opensWhereTheFormIsElseInSyncthing() {
        assertEquals("$root/DCIM", startDir("$root/DCIM/Holiday 2026", "$root/Syncthing", roots, disk))
        assertEquals("$root/Syncthing", startDir("", "$root/Syncthing", roots, disk))
        assertEquals("$root/Android", startDir("$root/Android/data/x", "$root/Syncthing", roots, disk))
        assertEquals("/storage/1234-ABCD", startDir("/storage/1234-ABCD/Films", "$root/Syncthing", roots, disk))
        assertEquals(root, startDir("/data/x", "/nowhere", roots, disk))
    }

    @Test
    fun crumbsAndNames() {
        assertEquals(listOf(root, "$root/DCIM", "$root/DCIM/Camera"), crumbs("$root/DCIM/Camera", roots[0]))
        assertEquals(listOf(root), crumbs(root, roots[0]))
        assertEquals("Internal storage" to "Syncthing/Holiday 2026", pathParts("$root/Syncthing/Holiday 2026", roots).let { it.first.en() to it.second })
        assertEquals("SanDisk SD card" to "", pathParts("/storage/1234-ABCD/", roots).let { it.first.en() to it.second })
        assertEquals(null to "/data/x", pathParts("/data/x", roots).let { it.first.en() to it.second })
    }

    @Test
    fun usualFoldersThatExist() {
        assertEquals(listOf("DCIM/Camera", "DCIM/Screenshots", "Pictures", "Download", "Documents", "Music").map { "$root/$it" }, quickPicks(disk))
        // Pixel phones keep screenshots in Pictures; a phone without Music gets no Music.
        val pixel = MapDisk(roots, mapOf("$root/Pictures" to 1, "$root/Pictures/Screenshots" to 1, "$root/DCIM/Screenshots" to 1, "$root/Download" to 0))
        assertEquals(listOf("Pictures/Screenshots", "Pictures", "Download").map { "$root/$it" }, quickPicks(pixel))
    }

    @Test
    fun newFolderNames() {
        val here = listOf("Download", "Music")
        assertNull(newFolderNameError(" Holiday 2026 ", here, removable = false))
        assertEquals("Required", newFolderNameError("  ", here, false).en())
        assertEquals("A folder with this name is already here", newFolderNameError("download", here, false).en())
        assertEquals("No /", newFolderNameError("a/b", here, false).en())
        assertEquals("Not . or ..", newFolderNameError("..", here, false).en())
        assertEquals("No line breaks or control characters", newFolderNameError("a\u202Eb", here, false).en())
        assertNull(newFolderNameError("What?", here, removable = false))
        assertEquals("Not on an SD card or USB stick: \\ : * ? \" < > |, or a . at the end", newFolderNameError("What?", here, removable = true).en())
        assertEquals("Not on an SD card or USB stick: \\ : * ? \" < > |, or a . at the end", newFolderNameError("end.", here, removable = true).en())
        assertEquals("At most 255 bytes", newFolderNameError("é".repeat(128), here, false).en())
    }

    /** A name the dialog takes makes a folder the form takes, one level down. */
    @Test
    fun acceptedNamesMakeValidFolders() = forAll(oneOf(anyText, { chars("abcAB .-_?:*/\\é😀\u0000\u202E", 0, 12) })) { name ->
        for (removable in listOf(false, true)) {
            if (newFolderNameError(name, emptyList(), removable) != null) continue
            val n = name.trim()
            assertFalse('/' in n)
            assertNull(folderPathError("$root/Download/$n"))
            if (removable) assertTrue(n.none { it in "\\:*?\"<>|" } && !n.endsWith('.'))
        }
    }
}
