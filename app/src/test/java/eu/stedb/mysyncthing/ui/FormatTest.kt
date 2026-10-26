package eu.stedb.mysyncthing.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test
    fun siBytes() {
        assertEquals("999 B", bytes(999))
        assertEquals("1.0 kB", bytes(1000))
        assertEquals("1.5 MB", bytes(1_500_000))
        assertEquals("2.0 GB", bytes(2_000_000_000))
    }

    @Test
    fun shortIdIsFirstGroup() {
        assertEquals("ABCDEFG", shortId("ABCDEFG-HIJKLMN-OPQRSTU"))
    }

    @Test
    fun internalStorageIsRelative() {
        assertEquals("/DCIM/Camera", displayPath("/storage/emulated/0/DCIM/Camera"))
        assertEquals("/storage/1234-ABCD/Music", displayPath("/storage/1234-ABCD/Music"))
    }

    @Test
    fun staggeredEditsDaysButStoresSeconds() {
        assertEquals("31536000", versioningStored("staggered", "365"))
        assertEquals("365", versioningShown("staggered", "31536000"))
        assertEquals("Staggered · 365 days", versioningSummary("staggered", mapOf("maxAge" to "31536000")))
        assertEquals("Trash can · keep forever", versioningSummary("trashcan", emptyMap()))
        assertEquals("Simple · 5 versions", versioningSummary("simple", emptyMap()))
    }

    @Test
    fun ratesAndDurationsCarryUnits() {
        assertEquals("1.2 MB/s", rate(1_200_000))
        assertEquals("45 s", duration(45))
        assertEquals("12 min", duration(720))
        assertEquals("3 h 5 min", duration(3 * 3600 + 300))
        assertEquals("2 d 4 h", duration(2 * 86_400 + 4 * 3600 + 59))
    }

    @Test
    fun timesAreDayFirstAnd24h() {
        val now = java.time.ZonedDateTime.parse("2026-10-25T14:00:00Z")
        val en = java.util.Locale.ENGLISH
        assertEquals("12:03", whenText("2026-10-25T12:03:41.5Z", now, en))
        assertEquals("yesterday, 22:10", whenText("2026-10-24T22:10:00Z", now, en))
        assertEquals("3 Oct, 08:05", whenText("2026-10-03T08:05:00Z", now, en))
        assertEquals("3 Oct 2025, 18:42", whenText("2025-10-03T18:42:00+00:00", now, en))
        assertEquals(null, whenText("0001-01-01T00:00:00Z", now, en))
        assertEquals(null, whenText("garbage", now, en))
    }

    @Test
    fun folderSettingTexts() {
        assertEquals("1 %", diskSizeText(eu.stedb.mysyncthing.core.DiskSize()))
        assertEquals("2.5 GB", diskSizeText(eu.stedb.mysyncthing.core.DiskSize(2.5, "GB")))
        assertEquals("Every hour", rescanText(3600))
        assertEquals("Every 6 hours", rescanText(21_600))
        assertEquals("Once a day", rescanText(86_400))
        assertEquals("Never", rescanText(0))
        assertEquals(listOf(3_600, 21_600, 86_400, 0, 600), rescanChoices(600).map { it.first })
        assertEquals("Smallest first", orderLabel("smallestFirst"))
    }

    @Test
    fun speedLimitsRoundTripInSi() {
        assertEquals("No limit", limitText(0))
        assertEquals(488, limitKib(500.0, "kB/s"))
        assertEquals("500 kB/s", limitText(488))
        assertEquals(977, limitKib(1.0, "MB/s"))
        assertEquals("1 MB/s", limitText(977))
        assertEquals("2.5 MB/s", limitText(limitKib(2.5, "MB/s")))
        assertEquals(1, limitKib(0.1, "kB/s"))
        assertEquals(0, limitKib(0.0, "MB/s"))
        assertEquals("Automatic", connectionsText(0))
        assertEquals(listOf(0, 1, 2, 3, 4, 8, 6), connectionChoices(6).map { it.first })
    }

    @Test
    fun discoveryServersNeedHttpsAndAHost() {
        val https = "https" + "://"
        assertTrue(isDiscoveryServer("${https}discovery.example.com/"))
        assertTrue(isDiscoveryServer("${https}discovery.example.com:8443/?id=ABC"))
        assertTrue(isDiscoveryServer("${https}192.0.2.10:8443/?insecure"))
        assertFalse(isDiscoveryServer("http" + "://example.com/"))
        assertFalse(isDiscoveryServer("${https}/path"))
        assertFalse(isDiscoveryServer("${https}example.com:99999/"))
        assertFalse(isDiscoveryServer("${https}user@example.com/"))
        assertFalse(isDiscoveryServer("${https}exa mple.com/"))
        assertFalse(isDiscoveryServer("discovery.example.com"))
    }

    @Test
    fun newFoldersFollowTheDefaultLocation() {
        assertEquals("/storage/emulated/0/Syncthing", pathBase(eu.stedb.mysyncthing.core.Folder(path = "~")))
        assertEquals("/storage/emulated/0/Sync", pathBase(eu.stedb.mysyncthing.core.Folder(path = "/storage/emulated/0/Sync/")))
        assertEquals("/storage/emulated/0/Sync/a_b", suggestedPath(" a/b ", "/storage/emulated/0/Sync"))
    }

    @Test
    fun untrustedShareRows() {
        val untrusted = eu.stedb.mysyncthing.core.Device("D", untrusted = true)
        assertEquals(null, encryptionText(untrusted.copy(untrusted = false), null))
        assertEquals("Untrusted: needs a password to share", encryptionText(untrusted, null))
        assertEquals("Encrypted · password set", encryptionText(untrusted, eu.stedb.mysyncthing.core.FolderDevice("D", encryptionPassword = "x")))
        assertEquals("No password: Syncthing stops sharing it", encryptionText(untrusted, eu.stedb.mysyncthing.core.FolderDevice("D")))
    }
}
