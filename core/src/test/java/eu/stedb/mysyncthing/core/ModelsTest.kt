package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    private val json = RestClient("127.0.0.1:1", "k").json

    /**
     * The core keeps speed limits in a Go int, 64-bit on phones; the web GUI takes
     * any value. As Kotlin Ints, 3,000,000,000 KiB/s failed to decode and with it
     * the whole device list or options.
     */
    @Test
    fun speedLimitsBeyondIntDecode() {
        val big = 3_000_000_000L
        assertEquals(big, json.decodeFromString<Device>("""{"deviceID":"X","maxSendKbps":$big,"maxRecvKbps":$big}""").maxSendKbps)
        assertEquals(big, json.decodeFromString<Options>("""{"maxSendKbps":$big,"maxRecvKbps":$big}""").maxRecvKbps)
    }
}
