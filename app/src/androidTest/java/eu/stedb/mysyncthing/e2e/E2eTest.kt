package eu.stedb.mysyncthing.e2e

import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.stedb.mysyncthing.MainActivity
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.SyncStatus
import eu.stedb.mysyncthing.core.SyncthingCore
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import eu.stedb.mysyncthing.core.R as CoreR

/**
 * The app on an emulator against a real Syncthing peer. scripts/e2e/run.py starts
 * the peer, installs the app and runs these one at a time, in this order, with
 * host-side steps in between (it changes a file on the peer before
 * [restoreVersion]); each phase builds on the one before. Taps go by label, as
 * a person reads the screen, never by position.
 */
@RunWith(AndroidJUnit4::class)
class E2eTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val peer = Peer(args.getString("peerId").orEmpty(), args.getString("peerKey").orEmpty(), args.getString("phoneAddress").orEmpty())
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(@StringRes id: Int) = app.getString(id)

    /** Waits for an enabled, tappable node showing [label] (its text or, for an icon, its description) and taps it. */
    private fun tap(label: String, timeout: Long = 20_000) {
        val m = (hasText(label) or hasContentDescription(label)) and hasClickAction() and isEnabled()
        rule.waitUntil(timeout) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(m).onFirst().scrolledTo().performClick()
    }

    /** Scrolled into view when inside a scrolling list; a top bar or dialog button is not. */
    private fun SemanticsNodeInteraction.scrolledTo() = apply { runCatching { performScrollTo() } }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    /**
     * Polls [done] (which may call the peer) every 0.5 s until it holds. Compose runs
     * on the test's clock here, which only moves when the test syncs with it: without
     * waitForIdle the app's start-up effect, which starts the service, never runs.
     */
    private fun eventually(what: String, timeoutMs: Long, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            rule.waitForIdle()
            if (runCatching(done).getOrDefault(false)) return
            check(System.currentTimeMillis() < end) { "timed out after ${timeoutMs / 1000} s: $what" }
            Thread.sleep(500)
        }
    }

    private fun shell(cmd: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).let { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }

    private fun phoneId(): String {
        eventually("core running", 60_000) { SyncthingCore.state.value is CoreState.Running }
        return (SyncthingCore.state.value as CoreState.Running).deviceId
    }

    private fun folderDir(): File {
        eventually("folder $FOLDER on the phone", 30_000) { SyncStatus.snapshot.value.folders.any { it.id == FOLDER } }
        return File(SyncStatus.snapshot.value.folders.first { it.id == FOLDER }.path)
    }

    /** Fresh install: the checklist, the usage question, pairing, accepting the offer, and sync both ways. */
    @Test
    fun pairAndSync() {
        tap(str(R.string.action_done))
        tap(str(R.string.action_no), timeout = 60_000) // usage reporting, asked once
        val me = phoneId()

        tap(str(R.string.tab_devices))
        tap(str(R.string.devices_add))
        field(str(R.string.device_id)).performTextInput(peer.id)
        field(str(R.string.name_optional)).performTextInput(PEER_NAME)
        tap(str(R.string.action_save))

        peer.addPhone(me)
        peer.share(FOLDER, me)
        eventually("peer connected to the phone", 90_000) { peer.connected(me) }

        tap(str(R.string.tab_folders))
        tap(str(CoreR.string.action_review), timeout = 60_000)
        tap(str(R.string.versioning_method))
        tap(str(R.string.versioning_trashcan))
        tap(str(R.string.action_save))

        val dir = folderDir()
        eventually("seed files on the phone", 120_000) {
            (0 until SEEDS).all { File(dir, "seed-%02d.txt".format(it)).isFile } && File(dir, VERSIONED).readText() == V1
        }
        File(dir, FROM_PHONE).writeText("written on the phone\n")
        eventually("$FROM_PHONE on the peer", 120_000) { peer.localFile(FOLDER, FROM_PHONE) != null }
        eventually("peer sees the phone up to date", 60_000) { peer.completion(FOLDER, me) == 100.0 }
    }

    /** The host changed versioned.txt on the peer; the phone's Trash can kept the old one, restored here. */
    @Test
    fun restoreVersion() {
        val file = File(folderDir(), VERSIONED)
        eventually("new $VERSIONED from the peer", 120_000) { file.readText() == V2 }

        tap(FOLDER_LABEL)
        tap(str(R.string.restore_versions))
        tap(VERSIONED, timeout = 30_000)
        tap(str(R.string.action_restore))

        eventually("old $VERSIONED back on the phone", 60_000) { file.readText() == V1 }
        // The restored file must sync too, not only come back on the phone.
        eventually("old $VERSIONED on the peer", 120_000) { peer.localFile(FOLDER, VERSIONED)?.optLong("size") == V1.length.toLong() }
    }

    /** Wi-Fi off leaves mobile data (or nothing), where syncing is off by default: the peer must lose the phone. */
    @Test
    fun runConditions() {
        val me = phoneId()
        eventually("peer connected", 90_000) { peer.connected(me) }
        try {
            shell("svc wifi disable")
            eventually("peer disconnected while on mobile data", 60_000) { !peer.connected(me) }
        } finally {
            shell("svc wifi enable")
        }
        eventually("peer connected again on Wi-Fi", 120_000) { peer.connected(me) }
    }

    /**
     * Typed text in dialogs survives the activity being recreated (rotation,
     * "Don't keep activities"). Also: an open, untouched text dialog draws no
     * frames. On Robolectric such a dialog re-measured itself every frame. Here
     * waitForIdle would time out on a loop in Compose, and the frame count
     * catches one in the window's own layout (about 60 frames a second).
     */
    @Test
    fun textDialogsKeepTypedText() {
        tap(FOLDER_LABEL)
        tap(str(R.string.name))
        rule.waitForIdle()
        Thread.sleep(2_000)
        shell("dumpsys gfxinfo ${app.packageName} reset")
        Thread.sleep(5_000)
        val frames = Regex("Total frames rendered: (\\d+)").find(shell("dumpsys gfxinfo ${app.packageName}"))?.groupValues?.get(1)?.toInt()
        assertTrue("frames drawn in 5 s with an idle rename dialog: $frames", frames != null && frames < IDLE_FRAMES)

        rule.onNode(hasSetTextAction()).performTextReplacement("Renamed")
        rule.activityRule.scenario.recreate()
        rule.onNode(hasSetTextAction()).assert(hasText("Renamed"))
        tap(str(R.string.action_cancel))

        // A list dialog keeps a typed entry that was not added yet.
        tap(str(R.string.action_back))
        tap(str(R.string.tab_settings))
        tap(str(R.string.listen_addresses))
        val entry = "tcp" + "://0.0.0.0:22111"
        rule.onNode(hasSetTextAction()).performTextInput(entry)
        rule.activityRule.scenario.recreate()
        rule.onNode(hasSetTextAction()).assert(hasText(entry))
        tap(str(R.string.action_cancel))

        // The recreated activities must go: StrictMode on Android 15 counted 3 right after the
        // recreations, which is only a leak if they outlive a few garbage collections.
        eventually("old activities collected", 30_000) {
            Runtime.getRuntime().gc()
            activities() == 1
        }
    }

    /** Activity objects alive in the app's process, as `dumpsys meminfo` counts them. */
    private fun activities(): Int? =
        Regex("Activities:\\s+(\\d+)").find(shell("dumpsys meminfo ${app.packageName}"))?.groupValues?.get(1)?.toInt()

    private companion object {
        const val FOLDER = "e2e"
        const val FOLDER_LABEL = "E2E"
        const val PEER_NAME = "e2e-peer"
        const val SEEDS = 20
        const val VERSIONED = "versioned.txt"
        const val FROM_PHONE = "from-phone.txt"
        // Different lengths, so the peer's file size tells which one it has.
        const val V1 = "version 1\n"
        const val V2 = "second version\n"
        const val IDLE_FRAMES = 30
    }
}
