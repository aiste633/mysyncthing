package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.ForkConditions
import eu.stedb.mysyncthing.core.ForkFolder
import eu.stedb.mysyncthing.core.ForkNotImported
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
        assertNotNull(backupPasswordError("", wrong = false))
        assertNull(backupPasswordError("x", wrong = false))
        assertNull(backupPasswordError("   ", wrong = false)) // spaces are a valid password, used as typed
        assertNull(backupPasswordError("x".repeat(MAX_BACKUP_PASSWORD), wrong = false))
        assertNotNull(backupPasswordError("x".repeat(MAX_BACKUP_PASSWORD + 1), wrong = false))
        assertEquals("Wrong password", backupPasswordError("x", wrong = true))
        // A failed attempt is reported whatever the field holds (the emulator check found it hidden behind "empty").
        assertEquals("Wrong password", backupPasswordError("", wrong = true))
    }

    /** Accepted exactly when 1 to MAX characters long and not known to be wrong; a wrong one always says so. */
    @Test
    fun passwordFieldProperty() {
        val r = Random(42)
        repeat(20_000) {
            val p = r.text(if (r.nextInt(10) == 0) MAX_BACKUP_PASSWORD + 50 else 40)
            val wrong = r.nextBoolean()
            val ok = p.isNotEmpty() && p.length <= MAX_BACKUP_PASSWORD && !wrong
            assertEquals("for ${p.length} chars, wrong=$wrong", ok, backupPasswordError(p, wrong) == null)
            if (wrong) assertEquals("Wrong password", backupPasswordError(p, true))
        }
    }

    @Test
    fun folderTextsProperty() {
        val r = Random(1)
        val types = listOf("sendreceive", "sendonly", "receiveonly", "receiveencrypted", "")
        repeat(5_000) {
            val path = "/storage/emulated/0/" + r.text(20)
            val f = ForkFolder("id", "", path, types[r.nextInt(types.size)], r.nextBoolean(), r.nextBoolean())
            val text = folderImportText(f)
            assertTrue(text.startsWith(wrapPath(displayPath(path)) + ": "))
            // A missing directory never claims to be found, and a found one never to be created.
            assertEquals(f.exists, "found" in text.substringAfterLast(": "))
        }
    }

    @Test
    fun conditionsTextProperty() {
        val r = Random(2)
        val networks = setOf("On unmetered Wi-Fi", "On Wi-Fi or Ethernet", "On unmetered networks", "On any network")
        repeat(1_000) {
            val c = ForkConditions(r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean())
            val lines = conditionsText(c)
            assertEquals(1, lines.count { it in networks })
            assertEquals(c.chargingOnly, "While charging" in lines)
            assertEquals(c.pausedByUser, "Syncing is paused" in lines)
            assertEquals(1, lines.count { it.startsWith("Start at boot") })
        }
    }

    @Test
    fun notImportedTexts() {
        val keys = listOf(
            "networks", "battery_only", "force_start", "roaming", "flight_mode", "master_sync", "wifi_allowlist",
            "schedule", "broadcasts", "tor", "socks_proxy", "http_proxy", "environment",
        )
        keys.forEach { assertFalse("$it has no text", notImportedText(ForkNotImported(it)).contains('_')) }
        listOf("folder_conditions", "folder_script", "device_conditions").forEach {
            assertTrue(notImportedText(ForkNotImported(it, "Photos")).endsWith("Photos"))
        }
        // A key a newer bridge sends still shows, with its subject.
        val r = Random(3)
        repeat(1_000) {
            val key = "new_" + r.text(10)
            val subject = r.text(10)
            val text = notImportedText(ForkNotImported(key, subject))
            assertTrue(key in text && subject in text)
        }
    }
}
