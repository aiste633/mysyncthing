package eu.stedb.mysyncthing.ui

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.stedb.mysyncthing.core.FolderProblem
import eu.stedb.mysyncthing.core.PendingFolder
import eu.stedb.mysyncthing.core.PendingFolderOffer
import eu.stedb.mysyncthing.core.ProblemNotifications
import eu.stedb.mysyncthing.core.RequestNotifications
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.requests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import eu.stedb.mysyncthing.core.R as CoreR

/**
 * A request's notification, or a stopped folder's, is that request's or folder's by its key. Up to
 * 0.5.0 it went by the key's hash code, which "Aa", "BB" and "C#" share: two such offers had one
 * notification, and Review and Ignore acted on the one posted last. Folder IDs come from other devices.
 */
@RunWith(AndroidJUnit4::class)
class NotificationIdentityTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm = app.getSystemService(NotificationManager::class.java)

    private fun offers(folders: Collection<String>) =
        Snapshot(loaded = true, pendingFolders = folders.associateWith { PendingFolder(mapOf(LAPTOP to PendingFolderOffer(it))) }).requests()

    private fun problems(folders: Collection<String>) = folders.map { FolderProblem(it, it, "folder marker missing") }

    /** The shown notifications of [kind], by key. */
    private fun shown(kind: String): Map<String, StatusBarNotification> =
        nm.activeNotifications.filter { it.tag?.startsWith("$kind:") == true }.associateBy { it.tag.removePrefix("$kind:") }

    private fun requestKey(folder: String) = "folder:$folder:$LAPTOP"

    private fun PendingIntent.extra(name: String): String? = shadowOf(this).savedIntent.getStringExtra(name)

    /** Every shown notification is one of [offered] or [stopped], with its own buttons, and nothing else of the app's changed. */
    private fun assertEachItsOwn(offered: Collection<String>, stopped: Collection<String>) {
        val requests = shown("request")
        assertEquals(offered.map(::requestKey).toSet(), requests.keys)
        for (folder in offered) {
            val n = requests.getValue(requestKey(folder)).notification
            assertEquals(folder, n.contentIntent.extra(RequestNotifications.EXTRA_REVIEW_FOLDER))
            assertEquals(LAPTOP, n.contentIntent.extra(RequestNotifications.EXTRA_REVIEW_DEVICE))
            assertEquals(n.contentIntent, n.actions[0].actionIntent)
            assertEquals(folder, n.actions[1].actionIntent.extra(RequestNotifications.EXTRA_REVIEW_FOLDER))
            assertEquals(app.getString(CoreR.string.request_folder_title, folder), n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        }
        val folders = shown("problem")
        assertEquals(stopped.toSet(), folders.keys)
        for (folder in stopped) {
            assertEquals(folder, folders.getValue(folder).notification.contentIntent.extra(ProblemNotifications.EXTRA_OPEN_FOLDER))
        }
        val slots = (requests.values + folders.values).map { it.id }
        assertEquals("one slot each", slots.size, slots.toSet().size)
        assertTrue("slots above the fixed IDs", slots.all { it >= 1000 })
    }

    @Test
    fun offersWithOneHashCodeHaveTheirOwnNotificationAndButtons() {
        assertEquals(requestKey("Aa").hashCode(), requestKey("BB").hashCode())
        RequestNotifications.update(app, offers(listOf("Aa", "BB")))
        assertEachItsOwn(offered = listOf("Aa", "BB"), stopped = emptyList())
    }

    @Test
    fun stoppedFoldersWithOneHashCodeHaveTheirOwnNotification() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        ProblemNotifications.updateFolders(app, problems(listOf("Aa", "BB")))
        assertEachItsOwn(offered = emptyList(), stopped = listOf("Aa", "BB"))

        // One recovers, the other's message changes: the same notification, redrawn.
        val slot = shown("problem").getValue("BB").id
        ProblemNotifications.updateFolders(app, listOf(FolderProblem("BB", "BB", "folder path missing")))
        assertEachItsOwn(offered = emptyList(), stopped = listOf("BB"))
        val now = shown("problem").getValue("BB")
        assertEquals(slot, now.id)
        assertEquals(app.getString(CoreR.string.problem_folder_missing), now.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun aHandledRequestGoesAndTheOthersKeepTheirs() {
        RequestNotifications.update(app, offers(listOf("Aa", "BB")))
        val before = shown("request")
        // "Aa" is handled while "C#" arrives: the new one does not take over the slot of the one that just went.
        RequestNotifications.update(app, offers(listOf("BB", "C#")))
        assertEachItsOwn(offered = listOf("BB", "C#"), stopped = emptyList())
        val after = shown("request")
        assertEquals(before.getValue(requestKey("BB")).id, after.getValue(requestKey("BB")).id)
        assertNotEquals(before.getValue(requestKey("Aa")).id, after.getValue(requestKey("C#")).id)

        RequestNotifications.update(app, emptyList())
        assertEachItsOwn(offered = emptyList(), stopped = emptyList())
    }

    @Test
    fun aRedrawKeepsEachNotification() {
        RequestNotifications.update(app, offers(listOf("Aa", "BB")))
        val before = shown("request").mapValues { it.value.id }
        RequestNotifications.update(app, offers(listOf("Aa", "BB")), repost = true)
        assertEachItsOwn(offered = listOf("Aa", "BB"), stopped = emptyList())
        assertEquals(before, shown("request").mapValues { it.value.id })
    }

    /** Requests and stopped folders coming and going in any order, next to the app's notices: each keeps its own. */
    @Test
    fun anyHistoryLeavesEachNotificationItsOwn() {
        ProblemNotifications.noFileAccess(app, true)
        val notice = nm.activeNotifications.single().notification.contentIntent
        val some: Gen<List<String>> = { POOL.filter { nextInt(3) == 0 } }
        forAll({ List(6) { some() to some() } }, runs = 300) { history ->
            for ((offered, stopped) in history) {
                RequestNotifications.update(app, offers(offered), repost = offered.size % 2 == 0)
                ProblemNotifications.updateFolders(app, problems(stopped))
                assertEachItsOwn(offered, stopped)
                assertNull(notice.extra(RequestNotifications.EXTRA_REVIEW_DEVICE))
                assertNull(notice.extra(ProblemNotifications.EXTRA_OPEN_FOLDER))
            }
        }
        assertEquals(1, nm.activeNotifications.count { it.tag == null })
    }

    @Test
    fun notificationsOfOlderVersionsAreRemovedAtStart() {
        val old = Notification.Builder(app, "requests-high").build()
        nm.notify("request", requestKey("Aa").hashCode(), old)
        nm.notify("request", 1000, old)
        nm.notify("folder", "Aa".hashCode(), old)
        ProblemNotifications.noFileAccess(app, true)
        // Before the clean-up they only keep their numbers taken.
        RequestNotifications.update(app, offers(listOf("Aa")))
        ProblemNotifications.updateFolders(app, problems(listOf("request", "folder")))
        assertEachItsOwn(offered = listOf("Aa"), stopped = listOf("request", "folder"))
        assertEquals(6, nm.activeNotifications.size)

        RequestNotifications.createChannel(app)
        ProblemNotifications.createChannel(app)
        assertEachItsOwn(offered = listOf("Aa"), stopped = listOf("request", "folder"))
        assertEquals(setOf(null, "request:${requestKey("Aa")}", "problem:request", "problem:folder"), nm.activeNotifications.map { it.tag }.toSet())
        assertEquals(4, nm.activeNotifications.size)
    }

    private companion object {
        /** Not a device ID, so the leak check has nothing to ask about. */
        const val LAPTOP = "LAPTOP1-X"
        /** The first three share a hash code, the next two another; the rest are names the tags themselves use. */
        val POOL = listOf("Aa", "BB", "C#", "AaAa", "BBBB", "photos", "request", "folder", "problem:x", "a:b:c")
    }
}
