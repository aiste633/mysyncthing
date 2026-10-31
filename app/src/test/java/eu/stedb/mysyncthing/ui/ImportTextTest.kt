package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.ForkFolder
import eu.stedb.mysyncthing.core.ForkNotImported
import eu.stedb.mysyncthing.core.PowerSource
import eu.stedb.mysyncthing.core.ProxySettings
import eu.stedb.mysyncthing.core.ProxyType
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.SyncWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImportTextTest {
    /** Strings of any length up to [max], from ASCII, accents, emoji (surrogate pairs) and whitespace. */
    private fun Random.text(max: Int): String {
        val pool = listOf("a", "Z", "0", " ", "\t", "é", "ß", "✓", "😀", "'", "\"", "\\", "\u0000")
        return buildString { repeat(nextInt(max + 1)) { append(pool[nextInt(pool.size)]) } }
    }

    @Test
    fun passwordField() {
        assertNotNull(backupPasswordError("", wrong = false).en())
        assertNull(backupPasswordError("x", wrong = false).en())
        assertNull(backupPasswordError("   ", wrong = false).en()) // spaces are a valid password, used as typed
        assertNull(backupPasswordError("x".repeat(MAX_BACKUP_PASSWORD), wrong = false).en())
        assertNotNull(backupPasswordError("x".repeat(MAX_BACKUP_PASSWORD + 1), wrong = false).en())
        assertEquals("Wrong password", backupPasswordError("x", wrong = true).en())
        // A failed attempt is reported whatever the field holds (the emulator check found it hidden behind "empty").
        assertEquals("Wrong password", backupPasswordError("", wrong = true).en())
    }

    /** Accepted exactly when 1 to MAX characters long and not known to be wrong; a wrong one always says so. */
    @Test
    fun passwordFieldProperty() {
        val r = Random(42)
        repeat(20_000) {
            val p = r.text(if (r.nextInt(10) == 0) MAX_BACKUP_PASSWORD + 50 else 40)
            val wrong = r.nextBoolean()
            val ok = p.isNotEmpty() && p.length <= MAX_BACKUP_PASSWORD && !wrong
            assertEquals("for ${p.length} chars, wrong=$wrong", ok, backupPasswordError(p, wrong).en() == null)
            if (wrong) assertEquals("Wrong password", backupPasswordError(p, true).en())
        }
    }

    @Test
    fun folderTextsProperty() {
        val r = Random(1)
        val types = listOf("sendreceive", "sendonly", "receiveonly", "receiveencrypted", "")
        repeat(5_000) {
            val path = "/storage/emulated/0/" + r.text(20)
            val f = ForkFolder("id", "", path, types[r.nextInt(types.size)], r.nextBoolean(), r.nextBoolean())
            val text = folderImportText(f).en()
            assertTrue(text.startsWith(wrapPath(displayPath(path).en()) + ": "))
            // A missing directory never claims to be found, and a found one never to be created.
            assertEquals(f.exists, "found" in text.substringAfterLast(": "))
        }
    }

    @Test
    fun conditionsTextProperty() {
        val r = Random(2)
        repeat(1_000) {
            val c = RunConditions(
                r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), PowerSource.entries.random(r), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(),
                r.nextBoolean(), List(r.nextInt(0, 3)) { SyncWindow(r.nextInt(0, 1440), r.nextInt(0, 1440), (1..7).shuffled(r).take(r.nextInt(1, 8)).toSet()) },
            )
            val proxy = if (r.nextBoolean()) ProxySettings() else ProxySettings(ProxyType.entries.random(r), "p", 1080, only = r.nextBoolean())
            val lines = conditionsText(c, proxy).map { it.en() }
            val networks = lines.single { it.startsWith("On ") }
            assertTrue(networks.startsWith("On unmetered Wi-Fi"))
            assertEquals(c.meteredWifi, "metered Wi-Fi" in networks.removePrefix("On unmetered Wi-Fi"))
            assertEquals(c.mobileData, "mobile data" in networks)
            assertEquals(c.mobileData && c.roaming, "roaming" in networks)
            assertEquals(c.power != PowerSource.Any, powerText(c.power).en() in lines)
            // A backup never pauses: the phone's own switch is kept.
            assertFalse(lines.any { "pause" in it.lowercase() && !it.startsWith("Pause in battery saver") })
            assertEquals(1, lines.count { it.startsWith("Start at boot") })
            assertEquals(proxy.type == ProxyType.None, "Proxy: none" in lines)
            val times = lines.single { it.startsWith("Sync times: ") }
            assertEquals(!c.timeSchedule, times == "Sync times: any time")
            if (c.timeSchedule) c.windows.forEach { assertTrue(windowText(it).en() in times) }
        }
    }

    @Test
    fun notImportedTexts() {
        val keys = listOf(
            "networks", "force_start", "force_stop", "flight_mode", "master_sync", "wifi_allowlist",
            "schedule", "broadcasts", "socks_proxy", "http_proxy", "environment",
            // go/bridge/importpolicy.go
            "default_folder_path", "default_folder_versioning", "default_folder_versions_path", "default_folder_marker",
            "default_device_addresses", "discovery_servers", "stun_servers", "listen_addresses", "local_networks",
            "local_discovery", "feature_flags",
        )
        keys.forEach { assertFalse("$it has no text", notImportedText(ForkNotImported(it)).en().contains('_')) }
        listOf(
            "folder_conditions", "folder_script", "device_conditions",
            "folder_id", "folder_filesystem", "folder_path", "folder_overlap", "folder_versioning", "folder_versions_path",
            "folder_marker", "device_addresses",
        ).forEach {
            val text = notImportedText(ForkNotImported(it, "Photos")).en()
            assertTrue(text, "Photos" in text && '_' !in text.replace("Photos", ""))
        }
        // A key a newer bridge sends still shows, with its subject.
        val r = Random(3)
        repeat(1_000) {
            val key = "new_" + r.text(10)
            val subject = r.text(10)
            val text = notImportedText(ForkNotImported(key, subject)).en()
            assertTrue(key in text && subject in text)
        }
    }
}
