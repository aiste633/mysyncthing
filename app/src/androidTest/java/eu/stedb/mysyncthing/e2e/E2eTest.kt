package eu.stedb.mysyncthing.e2e

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
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
import eu.stedb.mysyncthing.core.DeviceWindows
import eu.stedb.mysyncthing.core.MINUTES_PER_DAY
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.SyncWindow
import eu.stedb.mysyncthing.core.Versioning
import eu.stedb.mysyncthing.core.minuteOfDay
import eu.stedb.mysyncthing.core.withDeviceWindows
import eu.stedb.mysyncthing.core.NO_DEVICE_LIMIT
import eu.stedb.mysyncthing.core.Settings
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.SyncStatus
import eu.stedb.mysyncthing.core.SyncthingCore
import eu.stedb.mysyncthing.core.SyncthingService
import eu.stedb.mysyncthing.core.resolve
import eu.stedb.mysyncthing.ui.lastSeenText
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
     * The user's own pause of a device: from its card while syncing is allowed, and from its
     * screen while everything is paused (here by the user's pause of all syncing, enforced like
     * a run condition). A resume then used to be undone at once; now the choice holds afterwards.
     */
    @Test
    fun devicePause() {
        val me = phoneId()
        eventually("peer connected", 90_000) { peer.connected(me) }
        val pauseCard = app.getString(R.string.device_pause_named, PEER_NAME)
        val resumeCard = app.getString(R.string.device_resume_named, PEER_NAME)
        tap(str(R.string.tab_devices))
        tap(pauseCard)
        eventually("peer disconnected: paused on its card", 60_000) { !peer.connected(me) }
        try {
            pauseAll(true)
            // The card's toggle rests while everything is paused.
            rule.waitUntil(30_000) { rule.onAllNodes(hasContentDescription(resumeCard)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasContentDescription(resumeCard)).assertIsNotEnabled()
            // The card's pause went to the record when everything was paused.
            assertEquals("the card's pause recorded ${pauseState()}", setOf(peer.id), recorded())

            // Resumed on its screen while blocked: still paused now, resumed when the block ends.
            tap(PEER_NAME)
            tap(str(R.string.device_pause))
            eventually("the choice recorded", 10_000) { recorded() == emptySet<String>() }
            assertCorePaused("still paused while blocked")
            pauseAll(false)
            eventually("peer connected: resumed after the block", 120_000) { peer.connected(me) }

            // Paused on its screen while blocked: stays paused when the block ends.
            pauseAll(true)
            eventually("peer disconnected: all paused", 60_000) { !peer.connected(me) }
            tap(str(R.string.device_pause))
            eventually("the choice recorded", 10_000) { recorded() == setOf(peer.id) }
            pauseAll(false)
            eventually("block over", 30_000) { recorded() == null }
            assertCorePaused("still paused by the user after the block")
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end) {
                assertFalse("the peer reconnected to a device the user paused", peer.connected(me))
                Thread.sleep(1_000)
            }
        } finally {
            pauseAll(false)
        }
        tap(str(R.string.device_pause))
        eventually("peer connected again", 120_000) { peer.connected(me) }
    }

    /**
     * The Devices tab with the real core: the pause of all syncing as a toggle there, "last seen"
     * from the core's statistics on the card, the grey wait while no device is connected, and
     * the sync times opening their screen.
     */
    @Test
    fun devicesTab() {
        val me = phoneId()
        eventually("peer connected", 90_000) { peer.connected(me) }
        tap(str(R.string.tab_devices))
        try {
            // The pause of all syncing, from the tab.
            rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag("pauseAll")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasTestTag("pauseAll")).assertIsOff().performClick()
            eventually("stored", 10_000) { runBlocking { Settings(app).runConditions.first().pausedByUser } }
            eventually("peer disconnected: all paused", 60_000) { !peer.connected(me) }
            eventually("summary paused", 10_000) { SyncStatus.summary.value.state == SyncState.Paused }
            rule.onNode(hasTestTag("pauseAll")).assertIsOn()
            rule.onNode(hasContentDescription(app.getString(R.string.device_pause_named, PEER_NAME))).assertIsNotEnabled()

            // The card says when the core last saw the peer: at the disconnect, not before.
            eventually("last seen from the core", 30_000) {
                SyncStatus.snapshot.value.lastSeen[peer.id]?.let { Duration.between(it, Instant.now()).abs() < Duration.ofMinutes(5) } == true
            }
            val seen = lastSeenText(SyncStatus.snapshot.value.lastSeen.getValue(peer.id)).resolve(app).replaceFirstChar { it.lowercase() }
            rule.waitUntil(20_000) { rule.onAllNodes(hasText(seen, substring = true)).fetchSemanticsNodes().isNotEmpty() }

            rule.onNode(hasTestTag("pauseAll")).performClick()
            eventually("resumed", 10_000) { runBlocking { !Settings(app).runConditions.first().pausedByUser } }
            eventually("peer connected again", 120_000) { peer.connected(me) }

            // Away without a pause on the phone: a grey wait for 30 min, not red at once.
            peer.setPaused(me, true)
            eventually("peer gone", 60_000) { !peer.connected(me) }
            eventually("waiting for devices", 30_000) { SyncStatus.summary.value.state == SyncState.Waiting }
            SyncStatus.summary.value.let { s ->
                val last = checkNotNull(s.lastSeen) { "no last seen while waiting" }
                assertEquals(last.plus(NO_DEVICE_LIMIT), s.recheckAt)
            }
            rule.waitUntil(20_000) { rule.onAllNodes(hasText(app.getString(CoreR.string.status_waiting))).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            pauseAll(false)
            peer.setPaused(me, false)
        }
        eventually("peer connected after the wait", 120_000) { peer.connected(me) }
        eventually("up to date or syncing again", 60_000) { SyncStatus.summary.value.state != SyncState.Waiting }

        // The sync times open their screen; the button itself changes nothing.
        rule.onNode(hasTestTag("syncTimes")).performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(str(R.string.schedule_only_at_set_times))).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(runBlocking { Settings(app).runConditions.first().timeSchedule })
    }

    /**
     * A device's own sync windows with the real core: closed now, they pause the peer (its card's
     * button rests, its screen says why); turned off in their editor, it syncs again. Then own
     * windows that miss the global ones: the peer never syncs, and the card says so. The windows
     * are set around the phone's clock, every day, so the test does not wait for an alarm.
     */
    @Test
    fun deviceWindows() {
        val me = phoneId()
        eventually("peer connected", 90_000) { peer.connected(me) }
        val settings = Settings(app)
        val before = runBlocking { settings.runConditions.first() }
        val now = minuteOfDay(LocalDateTime.now())
        fun window(from: Int, to: Int) = SyncWindow(Math.floorMod(now + from, MINUTES_PER_DAY), Math.floorMod(now + to, MINUTES_PER_DAY), (1..7).toSet())
        fun set(c: (RunConditions) -> RunConditions) = runBlocking { check(settings.update(c)) }
        try {
            // Closed for the next two hours.
            set { it.withDeviceWindows(peer.id, DeviceWindows(true, listOf(window(120, 180)))) }
            eventually("peer disconnected: outside its own windows", 60_000) { !peer.connected(me) }
            eventually("held by its own windows", 10_000) { runBlocking { settings.conditionHolds() }?.holds(peer.id) == true }
            assertCorePaused("held by its own windows")
            val hold = checkNotNull(SyncStatus.snapshot.value.pause.devices[peer.id]) { "no hold published ${pauseState()}" }
            assertEquals(window(120, 180).start, hold.next?.let(::minuteOfDay))

            tap(str(R.string.tab_devices))
            rule.waitUntil(20_000) { rule.onAllNodes(hasText(hold.reason.resolve(app), substring = true)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasContentDescription(app.getString(R.string.device_pause_named, PEER_NAME))).assertIsNotEnabled()
            tap(PEER_NAME)
            rule.waitUntil(20_000) { rule.onAllNodes(hasText(str(R.string.device_block_note_own))).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasTestTag("deviceWindows")).scrolledTo().performClick()
            rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag("globalWindows")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasTestTag("windowsResult")).scrolledTo().assertIsDisplayed()
            rule.onNode(hasTestTag("ownWindows")).performClick()
            rule.onNode(hasTestTag("saveWindows")).performClick()
            eventually("own windows off, kept", 10_000) {
                runBlocking { settings.runConditions.first() }.deviceWindows[peer.id] == DeviceWindows(false, listOf(window(120, 180)))
            }
            eventually("peer connected: own windows off", 120_000) { peer.connected(me) }
            eventually("hold released", 10_000) { runBlocking { settings.conditionHolds() } == null }

            // Open now globally, but own windows only later: never.
            set {
                it.copy(timeSchedule = true, windows = listOf(window(-60, 60)))
                    .withDeviceWindows(peer.id, DeviceWindows(true, listOf(window(120, 180))))
            }
            eventually("peer disconnected: never syncs", 60_000) { !peer.connected(me) }
            assertTrue("not judged never ${pauseState()}", SyncStatus.snapshot.value.pause.devices[peer.id]?.never == true)
            rule.waitUntil(20_000) { rule.onAllNodes(hasText(app.getString(CoreR.string.device_never_syncs), substring = true)).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            set { before }
        }
        eventually("peer connected again", 120_000) { peer.connected(me) }
    }

    /**
     * Not part of the e2e run: scripts/e2e/monkey.py's "recreate" setup starts from here. Sync
     * times on and open now, the peer's own windows only later, so it never syncs: the monkey
     * starts on the "Never syncs" card, with the device and global editors (and the global
     * editor's "Save anyway" dialog) one tap away. Left set on purpose.
     */
    @Test
    fun monkeyNeverSyncs() {
        val me = phoneId()
        eventually("peer connected", 90_000) { peer.connected(me) }
        val now = minuteOfDay(LocalDateTime.now())
        fun window(from: Int, to: Int) = SyncWindow(Math.floorMod(now + from, MINUTES_PER_DAY), Math.floorMod(now + to, MINUTES_PER_DAY), (1..7).toSet())
        runBlocking {
            check(Settings(app).update {
                it.copy(timeSchedule = true, windows = listOf(window(-60, 600)))
                    .withDeviceWindows(peer.id, DeviceWindows(true, listOf(window(660, 720))))
            })
        }
        eventually("peer disconnected: never syncs", 60_000) { !peer.connected(me) }
        assertTrue("not judged never ${pauseState()}", SyncStatus.snapshot.value.pause.devices[peer.id]?.never == true)
    }

    private fun pauseAll(paused: Boolean) = runBlocking { check(Settings(app).update { it.copy(pausedByUser = paused) }) }

    private fun recorded(): Set<String>? = runBlocking { Settings(app).conditionPause() }

    /**
     * Asserts the peer's pause in the phone's core, read from the core itself, and then that the
     * screens' view (SyncStatus, refreshed on the core's events) agrees within a few seconds. Each
     * failure says which of the two was wrong and the state around it, as CI's artifact is not
     * always at hand.
     */
    private fun assertCorePaused(why: String, paused: Boolean = true) {
        val core = (SyncthingCore.state.value as CoreState.Running).client
        val inCore = runBlocking { core.devices() }.first { it.deviceID == peer.id }.paused
        assertEquals("$why (the core) ${pauseState()}", paused, inCore)
        try {
            eventually("screens follow the core", 10_000) { SyncStatus.snapshot.value.devices.find { it.deviceID == peer.id }?.paused == paused }
        } catch (e: IllegalStateException) {
            throw AssertionError("$why (the screens' view) ${pauseState()}", e)
        }
    }

    private fun pauseState(): String {
        val s = SyncStatus.snapshot.value
        return "[snapshot: ${s.devices.find { it.deviceID == peer.id }?.paused}, record: ${recorded()?.map { it.take(7) }}, " +
            "blocked: ${s.pause.allBlocked}, published record: ${s.pause.holds?.userPaused?.map { it.take(7) }}]"
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
     * Remove on a folder's or a device's screen goes back to its tab, with the tab's list on it. The
     * screen used to leave twice: at the tap, and again when the core's change reached the snapshot
     * during its exit animation. That popped the tab too: Folders went blank under the tab bar, and a
     * removed device showed Folders. A folder and a device of their own, so the peer's stay.
     */
    @Test
    fun removeGoesBackToTheTab() {
        phoneId()
        val core = (SyncthingCore.state.value as CoreState.Running).client
        val path = File(folderDir().parentFile, REMOVE_FOLDER).path
        try {
            runBlocking {
                core.addFolder(REMOVE_FOLDER, REMOVE_LABEL, path, emptyList(), "sendreceive", Versioning())
                core.addDevice(REMOVE_DEVICE, REMOVE_NAME)
            }
            eventually("new folder and device on the screens", 30_000) {
                SyncStatus.snapshot.value.let { s -> s.folders.any { it.id == REMOVE_FOLDER } && s.devices.any { it.deviceID == REMOVE_DEVICE } }
            }

            tap(str(R.string.tab_folders))
            tap(REMOVE_LABEL)
            tap(str(R.string.folder_remove))
            tap(str(R.string.action_remove))
            eventually("folder gone from the core", 30_000) { runBlocking { core.folders() }.none { it.id == REMOVE_FOLDER } }
            eventually("folder gone from the screens", 30_000) { SyncStatus.snapshot.value.folders.none { it.id == REMOVE_FOLDER } }
            rule.onAllNodes(hasText(FOLDER_LABEL)).onFirst().assertIsDisplayed()

            tap(str(R.string.tab_devices))
            tap(REMOVE_NAME)
            tap(str(R.string.device_remove))
            tap(str(R.string.action_remove))
            eventually("device gone from the core", 30_000) { runBlocking { core.devices() }.none { it.deviceID == REMOVE_DEVICE } }
            eventually("device gone from the screens", 30_000) { SyncStatus.snapshot.value.devices.none { it.deviceID == REMOVE_DEVICE } }
            rule.onAllNodes(hasText(PEER_NAME)).onFirst().assertIsDisplayed()
            rule.onNode(hasTestTag("pauseAll")).assertIsDisplayed()
        } finally {
            runBlocking {
                runCatching { core.removeFolder(REMOVE_FOLDER) }
                runCatching { core.removeDevice(REMOVE_DEVICE) }
            }
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
    /**
     * The user's stop, and a start right after one. The core takes seconds to shut down on a slow
     * emulator; that runs off the main thread (CI monkey, API 36: the stop ran on it and froze the app
     * past 5 s, an ANR), and a start that comes during a stop starts the core again once the stop is
     * done (it used to be lost: the stop ended the service after it).
     */
    @Test
    fun stopAndStart() {
        val me = phoneId()
        eventually("peer connected", 90_000) { peer.connected(me) }
        val main = Handler(Looper.getMainLooper())
        var worst = 0L
        val stopAt = System.currentTimeMillis()
        SyncthingService.stopByUser(app)
        val end = stopAt + 30_000
        while (SyncthingCore.state.value !is CoreState.Stopped && System.currentTimeMillis() < end) {
            val asked = System.nanoTime()
            val answered = CountDownLatch(1)
            main.post { answered.countDown() }
            assertTrue("the main thread did not answer within 5 s during the stop", answered.await(5, TimeUnit.SECONDS))
            worst = maxOf(worst, (System.nanoTime() - asked) / 1_000_000)
            Thread.sleep(20)
        }
        val took = System.currentTimeMillis() - stopAt
        assertTrue("core stopped", SyncthingCore.state.value is CoreState.Stopped)
        assertTrue("the main thread waited $worst ms during the stop", worst < MAIN_THREAD_MAX_MS)
        // The core's own shutdown takes up to 10 s; the stop must not also wait for the event long-poll (60 s).
        assertTrue("the stop took $took ms", took < STOP_MAX_MS)

        SyncthingService.start(app)
        eventually("core running again", 60_000) { SyncthingCore.state.value is CoreState.Running }
        eventually("peer connected again", 120_000) { peer.connected(me) }
        val before = SyncthingCore.state.value
        SyncthingService.stopByUser(app)
        SyncthingService.start(app)
        // A new run, not the old one still shutting down.
        eventually("core running after a start that came during a stop", 60_000) {
            SyncthingCore.state.value.let { it is CoreState.Running && it !== before }
        }
        eventually("peer connected after it", 120_000) { peer.connected(me) }
        assertFalse("still stopped by the user", runBlocking { Settings(app).stoppedByUser.first() })
    }

    private fun activities(): Int? =
        Regex("Activities:\\s+(\\d+)").find(shell("dumpsys meminfo ${app.packageName}"))?.groupValues?.get(1)?.toInt()

    private companion object {
        /** The open network every emulator image offers. */
        const val EMULATOR_WIFI = "AndroidWifi"
        const val FOLDER = "e2e"
        const val FOLDER_LABEL = "E2E"
        /** Frames, GC and a slow CI emulator stay well under it; a core stop on the main thread took seconds. */
        const val MAIN_THREAD_MAX_MS = 1_000L
        /** The core shuts down within 10 s (upstream's close timeout); waiting for the event long-poll could add 60 s. */
        const val STOP_MAX_MS = 20_000L
        const val PEER_NAME = "e2e-peer"
        const val REMOVE_FOLDER = "e2e-remove"
        const val REMOVE_LABEL = "To remove"
        const val REMOVE_NAME = "To remove too"
        /** Valid, and no device ever connects with it (the unit tests' laptop). */
        const val REMOVE_DEVICE = "EUYDWRS-RLRTXET-7MISOPK-TNF7ZLL-K6B27WA-EGBOIRY-NHBBU4W-LEN55AT"
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
