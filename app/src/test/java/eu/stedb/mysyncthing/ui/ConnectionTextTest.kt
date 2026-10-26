package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTextTest {
    private val lan = Connection(connected = true, address = "192.0.2.10:22000", type = "tcp-client", isLocal = true)

    @Test fun cardsNameOnlyTheKindOfConnection() {
        assertEquals("LAN", connectionText(lan, withAddress = false))
        assertEquals("Relay", connectionText(lan.copy(type = "relay-client", isLocal = false), withAddress = false))
        assertEquals("Internet", connectionText(lan.copy(isLocal = false), withAddress = false))
    }

    @Test fun theDeviceScreenAddsTheAddress() {
        assertEquals("LAN · 192.0.2.10:22000", connectionText(lan))
    }

    @Test fun nothingWhenDisconnected() {
        assertNull(connectionText(lan.copy(connected = false)))
    }
}
