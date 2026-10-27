package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.FolderDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionTest {
    private val trusted = Device(deviceID = "PEER")
    private val untrusted = trusted.copy(untrusted = true)
    private val encrypted = FolderDevice("PEER", encryptionPassword = "pw")
    private val plain = FolderDevice("PEER")

    @Test
    fun aShareStaysEncryptedAfterTheDeviceIsTrustedAgain() {
        assertEquals("Encrypted · password set", encryptionText(trusted, encrypted).en())
        assertTrue(sharesEncrypted(trusted, encrypted))
    }

    @Test
    fun plainSharesWithTrustedDevicesSayNothing() {
        assertEquals(null, encryptionText(trusted, plain).en())
        assertEquals(null, encryptionText(trusted, null).en())
        assertFalse(sharesEncrypted(trusted, plain))
        assertFalse(sharesEncrypted(trusted, null))
    }

    @Test
    fun untrustedDevicesAlwaysAskForAPassword() {
        assertEquals("Untrusted: needs a password to share", encryptionText(untrusted, null).en())
        assertEquals("No password: Syncthing stops sharing it", encryptionText(untrusted, plain).en())
        assertEquals("Encrypted · password set", encryptionText(untrusted, encrypted).en())
        assertTrue(sharesEncrypted(untrusted, null))
    }
}
