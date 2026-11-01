package eu.stedb.mysyncthing.e2e

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.stedb.mysyncthing.MainActivity
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.Settings
import eu.stedb.mysyncthing.core.SyncStatus
import eu.stedb.mysyncthing.core.SyncthingCore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /**
     * Every phase starts on Wi-Fi. run.py turns off the emulator's internet check: when Wi-Fi
     * failed it, mobile data stayed the default network and the run conditions paused syncing.
     *
     * Wi-Fi turned back on (run.py, [runConditions]) does not always join the emulator's network
     * again by itself: on API 35 in CI it once kept scanning every 20 s for a minute without
     * trying to join. So it is joined by name; a failure says what Wi-Fi saw.
     */
    @Before
    fun onWifi() {
        if (!wifiIsDefault()) {
            shell("svc wifi enable")
            eventually("Wi-Fi on", 30_000) { "Wifi is enabled" in shell("cmd wifi status") }
            shell("cmd wifi connect-network $EMULATOR_WIFI open")
        }
        try {
            eventually("Wi-Fi is the default network", 60_000, ::wifiIsDefault)
        } catch (e: IllegalStateException) {
            throw IllegalStateException("${e.message}\n${shell("cmd wifi status")}\n${shell("cmd wifi list-scan-results")}", e)
        }
    }

    private fun wifiIsDefault(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

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
     *
     * Checked at once after each recreation, with no wait: a dialog on values read
     * from the core (Settings, the defaults) is there on the recreated screen's
     * first frame, and so is the scroll position. Their reads are network calls
     * Compose does not wait for; once read in the screen only, the dialog came
     * back late and a slow emulator failed here (API 35 on CI).
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
        rule.onNode(hasSetTextAction()).assert(hasText(entry))
        rule.activityRule.scenario.recreate()
        rule.onNode(hasSetTextAction()).assert(hasText(entry))
        // The "?" lists the forms the list takes, and closing it keeps what was typed.
        tap(str(R.string.syntax_title))
        rule.onNodeWithText("tcp" + "://:22001").assertIsDisplayed()
        tap(str(R.string.action_close))
        rule.onNode(hasSetTextAction()).assert(hasText(entry))
        tap(str(R.string.action_cancel))
        rule.onNodeWithText(str(R.string.listen_addresses)).assertIsDisplayed()

        // The same in a defaults screen.
        tap(str(R.string.defaults_devices))
        tap(str(R.string.device_addresses))
        val address = "tcp" + "://192.0.2.10:22000"
        rule.onNode(hasSetTextAction()).performTextInput(address)
        rule.onNode(hasSetTextAction()).assert(hasText(address))
        rule.activityRule.scenario.recreate()
        rule.onNode(hasSetTextAction()).assert(hasText(address))
        tap(str(R.string.action_cancel))

        // The recreated activities must go: StrictMode on Android 15 counted 3 right after the
        // recreations, which is only a leak if they outlive a few garbage collections.
        eventually("old activities collected", 30_000) {
            Runtime.getRuntime().gc()
            activities() == 1
        }
    }

    /**
     * scripts/e2e/upgrade.py installed this build over the last release, which ran its own
     * [pairAndSync]: the setup, the identity, the peer and the folder are still there, the
     * database too (no "moved from another phone"), and files sync both ways.
     */
    @Test
    fun afterUpgrade() {
        val settings = Settings(app)
        runBlocking {
            assertTrue("setup checklist done before the update", settings.setupDone.first())
            assertFalse("an update is no move to another phone", settings.movedFromOtherPhone.first())
        }
        assertEquals("device ID", args.getString("phoneId"), phoneId())
        eventually("peer and folder in the core's config", 30_000) {
            val s = SyncStatus.snapshot.value
            s.devices.any { it.deviceID == peer.id && it.name == PEER_NAME } && s.folders.any { it.id == FOLDER }
        }
        assertEquals("versioning", "trashcan", SyncStatus.snapshot.value.folders.first { it.id == FOLDER }.versioning.type)

        val dir = folderDir()
        eventually("$FROM_PEER_AFTER on the phone", 120_000) { File(dir, FROM_PEER_AFTER).isFile }
        File(dir, FROM_PHONE_AFTER).writeText("written on the phone after the update\n")
        eventually("$FROM_PHONE_AFTER on the peer", 120_000) { peer.localFile(FOLDER, FROM_PHONE_AFTER) != null }
    }

    /** Stop in Settings; upgrade.py then updates the app, which must leave it stopped. */
    @Test
    fun stopSync() {
        phoneId()
        tap(str(R.string.tab_settings))
        tap(str(R.string.settings_stop))
        eventually("core stopped", 30_000) { SyncthingCore.state.value is CoreState.Stopped }
    }

    /** Start again in Settings after [stopSync]. */
    @Test
    fun startSync() {
        tap(str(R.string.tab_settings))
        tap(str(R.string.settings_start))
        phoneId()
    }

    /** Turns "Start at boot" off, with the core running. */
    @Test
    fun startOnBootOff() {
        phoneId()
        tap(str(R.string.tab_settings))
        tap(str(R.string.settings_boot))
        eventually("Start at boot off", 10_000) { runBlocking { !Settings(app).runConditions.first().startOnBoot } }
    }

    /** Activity objects alive in the app's process, as `dumpsys meminfo` counts them. */
    private fun activities(): Int? =
        Regex("Activities:\\s+(\\d+)").find(shell("dumpsys meminfo ${app.packageName}"))?.groupValues?.get(1)?.toInt()

    private companion object {
        /** The open network every emulator image offers. */
        const val EMULATOR_WIFI = "AndroidWifi"
        const val FOLDER = "e2e"
        const val FOLDER_LABEL = "E2E"
        const val PEER_NAME = "e2e-peer"
        const val SEEDS = 20
        const val VERSIONED = "versioned.txt"
        const val FROM_PHONE = "from-phone.txt"
        const val FROM_PEER_AFTER = "from-peer-after-update.txt" // upgrade.py's
        const val FROM_PHONE_AFTER = "from-phone-after-update.txt"
        // Different lengths, so the peer's file size tells which one it has.
        const val V1 = "version 1\n"
        const val V2 = "second version\n"
        const val IDLE_FRAMES = 30
    }
}
