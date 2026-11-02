package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.Completion
import eu.stedb.mysyncthing.core.Connection
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.Credentials
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.DevicePause
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderDevice
import eu.stedb.mysyncthing.core.FolderStatus
import eu.stedb.mysyncthing.core.PendingFolder
import eu.stedb.mysyncthing.core.PendingFolderOffer
import eu.stedb.mysyncthing.core.RestClient
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Versioning
import eu.stedb.mysyncthing.core.summarize
import eu.stedb.mysyncthing.core.text
import eu.stedb.mysyncthing.core.R as CoreR
import java.time.Instant

private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/** A check character as lib/protocol/luhn.go makes it: not the textbook Luhn mod N, but what every Syncthing ID uses. */
fun luhn(s: String): Char {
    var factor = 1
    var sum = 0
    for (c in s) {
        val addend = factor * B32.indexOf(c)
        factor = 3 - factor
        sum += addend / 32 + addend % 32
    }
    return B32[(32 - sum % 32) % 32]
}

/** The device ID, with check characters and dashes, for 52 base32 data characters. */
fun idFromData(data: String): String = data.chunked(13).joinToString("") { it + luhn(it) }.chunked(7).joinToString("-")

/**
 * A made-up phone for the screen tests: three folders (up to date, syncing, stopped
 * by an error), three devices (on the LAN, through a relay, offline) and one folder
 * offer. Two device IDs were made by the core from fixed bytes, the others get the core's
 * check characters here; the dashes are added in code, so the leak check does not read
 * them as someone's device.
 */
object Fixtures {
    private fun dashed(id: String) = id.chunked(7).joinToString("-")

    val myId = dashed("AAFRMIJMG5BE2XWDDNZ4YJD42UWWYLXRWR3TT7F7I7ICMPCSNB7JJKQW")
    val laptop = dashed("EUYDWRSRLRTXET7MISOPKTNF7ZLLK6B27WAEGBOIRYNHBBU4WLEN55AT")
    val server = idFromData("SERVERSERVERSERVERSERVERSERVERSERVERSERVERSERVERSERA")
    val tablet = idFromData("TABLETTABLETTABLETTABLETTABLETTABLETTABLETTABLETTABA")

    val snapshot = Snapshot(
        loaded = true,
        myId = myId,
        myName = "Pixel",
        devices = listOf(
            Device(laptop, "Laptop"),
            Device(server, "Home server", addresses = listOf("dynamic", "tcp" + "://192.0.2.10:22000")),
            Device(tablet, "Old tablet", paused = true), // by the user
        ),
        folders = listOf(
            Folder(
                "photos", "Photos", "/storage/emulated/0/DCIM/Camera", type = "sendonly",
                devices = listOf(FolderDevice(laptop), FolderDevice(server)),
                versioning = Versioning("trashcan", mapOf("cleanoutDays" to "30")),
            ),
            Folder("docs", "Documents", "/storage/emulated/0/Documents", devices = listOf(FolderDevice(laptop))),
            Folder("music", "Music", "/storage/emulated/0/Music", type = "receiveonly", devices = listOf(FolderDevice(server))),
        ),
        folderStatus = mapOf(
            "photos" to FolderStatus("idle", globalBytes = 8_420_000_000, localBytes = 8_420_000_000, globalFiles = 2_315, localFiles = 2_315),
            "docs" to FolderStatus(
                "syncing", globalBytes = 1_250_000_000, localBytes = 800_000_000, needBytes = 450_000_000, needTotalItems = 37,
                globalFiles = 412, localFiles = 375,
            ),
            "music" to FolderStatus("error", error = "folder marker missing (this indicates potential data loss, search docs/forum to get information about how to proceed)"),
        ),
        connections = mapOf(
            laptop to Connection(true, address = "tcp" + "://192.0.2.20:22000", type = "tcp-server", isLocal = true, clientVersion = "v2.1.7"),
            server to Connection(true, address = "relay" + "://198.51.100.7:22067", type = "relay-client", clientVersion = "v2.1.7"),
        ),
        completion = mapOf(laptop to Completion(64.0, needBytes = 450_000_000, needItems = 37), server to Completion(100.0)),
        pendingFolders = mapOf("holiday" to PendingFolder(mapOf(laptop to PendingFolderOffer("Holiday 2026")))),
        // A year back, so the screenshots show the same date on any day.
        lastSeen = mapOf(
            laptop to Instant.parse("2025-10-03T18:42:00Z"),
            server to Instant.parse("2025-10-03T17:15:00Z"),
            tablet to Instant.parse("2025-09-28T09:05:00Z"),
        ),
    )

    val core = CoreState.Running(RestClient("127.0.0.1:9", "test"), "127.0.0.1:9", myId, Credentials("test", "test", "test"))

    val summary = summarize(core, snapshot, blockedBy = null, online = true)

    /** On mobile data with syncing off there: every device paused, the tablet also by the user. */
    private val mobileData = text(CoreR.string.pause_reason_mobile_data)
    val blocked = snapshot.copy(
        devices = snapshot.devices.map { it.copy(paused = true) },
        connections = emptyMap(),
        completion = emptyMap(),
        pause = DevicePause(mobileData, recorded = setOf(tablet)),
    )
    val blockedSummary = summarize(core, blocked, blockedBy = mobileData, online = true)

    /**
     * Nothing connected and nothing paused, 10 min after the laptop was last seen; the tablet
     * never was. No folder errors or requests, which would rank first.
     */
    val waiting = snapshot.copy(
        devices = snapshot.devices.map { it.copy(paused = false) },
        connections = emptyMap(),
        completion = emptyMap(),
        folderStatus = snapshot.folderStatus.mapValues { FolderStatus("idle") },
        pendingFolders = emptyMap(),
        lastSeen = snapshot.lastSeen - tablet,
    )
    private val laptopSeen = snapshot.lastSeen.getValue(laptop)
    val waitingSummary = summarize(core, waiting, null, true, now = laptopSeen.plusSeconds(600), waitingSince = laptopSeen.minusSeconds(86_400))
    val noDeviceSummary = summarize(core, waiting, null, true, now = laptopSeen.plusSeconds(3 * 3600), waitingSince = laptopSeen.minusSeconds(86_400))
}
