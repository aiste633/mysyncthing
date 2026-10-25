package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryTest {
    private val running = CoreState.Running(RestClient("127.0.0.1:1", "k"), "127.0.0.1:1", "ME", Credentials("k", "u", "p"))
    private val server = Device("SERVER1-X", name = "server")
    private val laptop = Device("LAPTOP1-X", name = "laptop")
    private val photos = Folder("photos", path = "/storage/emulated/0/DCIM")
    private val idle = FolderStatus(state = "idle", globalBytes = 1000, localBytes = 1000)

    private fun snap(
        devices: List<Device> = listOf(server),
        connected: Set<String> = setOf(server.deviceID),
        status: FolderStatus = idle,
        completion: Map<String, Completion> = emptyMap(),
        pendingDevices: Map<String, PendingDevice> = emptyMap(),
    ) = Snapshot(
        loaded = true,
        myId = "ME",
        devices = devices,
        folders = listOf(photos),
        folderStatus = mapOf(photos.id to status),
        connections = devices.associate { it.deviceID to Connection(connected = it.deviceID in connected) },
        completion = completion,
        pendingDevices = pendingDevices,
    )

    private fun line(s: Snapshot, blockedBy: String? = null, online: Boolean = true) =
        summarize(running, s, blockedBy, online).let { it.state to it.line() }

    @Test
    fun upToDateNamesTheOnlyDevice() {
        assertEquals(SyncState.UpToDate to "Up to date · server", line(snap()))
        assertEquals(
            SyncState.UpToDate to "Up to date · 2 devices",
            line(snap(devices = listOf(server, laptop), connected = setOf(server.deviceID, laptop.deviceID))),
        )
    }

    @Test
    fun noNetworkOrNoConnectionIsNotSyncing() {
        assertEquals(SyncState.Problem to "No network", line(snap(connected = emptySet()), online = false))
        assertEquals(SyncState.Problem to "Not connected", line(snap(connected = emptySet())))
    }

    @Test
    fun runConditionWinsOverNetwork() {
        assertEquals(SyncState.Paused to "Paused: Not on Wi-Fi", line(snap(connected = emptySet()), "Not on Wi-Fi", online = false))
    }

    @Test
    fun userPausedAllDevices() {
        assertEquals(SyncState.Paused to "Paused", line(snap(devices = listOf(server.copy(paused = true)), connected = emptySet())))
    }

    @Test
    fun downloadAndUploadProgress() {
        val pulling = idle.copy(state = "syncing", needBytes = 250)
        assertEquals(SyncState.Syncing to "Syncing 75 % · server", line(snap(status = pulling)))
        val pushing = mapOf(server.deviceID to Completion(completion = 40.5, needBytes = 600))
        assertEquals(SyncState.Syncing to "Syncing 40 % · server", line(snap(completion = pushing)))
    }

    @Test
    fun requestsNeedInputAndErrorsAreProblems() {
        val pending = mapOf("NEW1234-X" to PendingDevice(name = "tablet"))
        assertEquals(SyncState.NeedsInput to "New device wants to connect · server", line(snap(pendingDevices = pending)))
        assertEquals(SyncState.Problem to "3 items failed to sync · server", line(snap(status = idle.copy(errors = 3))))
        assertEquals(SyncState.NeedsInput to "No devices added", line(snap(devices = emptyList())))
    }

    @Test
    fun coreNoticesAreProblemsUnlessPaused() {
        val notices = snap().copy(systemErrors = listOf(SystemError("2026-10-25T12:03:00Z", "Listen (BEP/tcp): address already in use")))
        assertEquals(SyncState.Problem to "1 problem · server", line(notices))
        assertEquals(SyncState.Paused to "Paused: Not on Wi-Fi", line(notices, "Not on Wi-Fi"))
    }
}
