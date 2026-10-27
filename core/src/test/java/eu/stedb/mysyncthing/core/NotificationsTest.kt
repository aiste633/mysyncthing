package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationsTest {
    private val photos = Folder("photos", label = "Photos", path = "/storage/emulated/0/DCIM")
    private val music = Folder("music", path = "/storage/emulated/0/Music")

    @Test
    fun onlyStoppedActiveFoldersAreProblems() {
        val s = Snapshot(
            loaded = true,
            folders = listOf(photos, music, music.copy(id = "paused", paused = true)),
            folderStatus = mapOf(
                "photos" to FolderStatus(state = "error", error = "folder marker missing"),
                // Single failed files are retried, so they don't notify.
                "music" to FolderStatus(state = "idle", errors = 3),
                "paused" to FolderStatus(state = "error", error = "folder path missing"),
            ),
        )
        assertEquals(listOf(FolderProblem("photos", "Photos", "folder marker missing")), s.folderProblems())
    }

    @Test
    fun offerOfAFolderAlreadyHereAsksToShareIt() {
        val laptop = Device("LAPTOP1-X", name = "laptop")
        val offers = mapOf(
            "photos" to PendingFolder(mapOf(laptop.deviceID to PendingFolderOffer("Photos"))),
            "new" to PendingFolder(mapOf(laptop.deviceID to PendingFolderOffer("New"))),
        )
        val s = Snapshot(loaded = true, devices = listOf(laptop), folders = listOf(photos), pendingFolders = offers)
        assertEquals(
            listOf(
                "\"Photos\" shared with you" to "From laptop. You already have this folder; tap Review to share it.",
                "\"New\" shared with you" to "From laptop. Tap Review to choose where to keep it.",
            ),
            s.requests().map { it.title.en() to it.text.en() },
        )
    }
}
