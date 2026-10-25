package eu.stedb.mysyncthing.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderDevicesTest {
    /** What the core's PATCH does with a list: each sent entry overwrites only its own keys on the stored entry at that position. */
    private fun goMerge(stored: List<FolderDevice>, sent: JsonArray): List<FolderDevice> = sent.mapIndexed { i, e ->
        val o = e.jsonObject
        val old = stored.getOrNull(i) ?: FolderDevice("")
        FolderDevice(
            o["deviceID"]?.jsonPrimitive?.content ?: old.deviceID,
            o["introducedBy"]?.jsonPrimitive?.content ?: old.introducedBy,
            o["encryptionPassword"]?.jsonPrimitive?.content ?: old.encryptionPassword,
        )
    }

    @Test
    fun stoppingAnEncryptedSharePassesNoPasswordOn() {
        val stored = listOf(FolderDevice("UNTRUSTED", encryptionPassword = "pw"), FolderDevice("PEER"), FolderDevice("ME"))
        val after = goMerge(stored, folderDevicesJson(stored.drop(1)))
        assertEquals(listOf(FolderDevice("PEER"), FolderDevice("ME")), after)
    }

    @Test
    fun everyEntryCarriesEveryField() {
        val sent = folderDevicesJson(listOf(FolderDevice("A"), FolderDevice("B", "A", "pw")))
        assertEquals(
            JsonObject(mapOf("deviceID" to JsonPrimitive("A"), "introducedBy" to JsonPrimitive(""), "encryptionPassword" to JsonPrimitive(""))),
            sent[0],
        )
        assertEquals(setOf("deviceID", "introducedBy", "encryptionPassword"), sent[1].jsonObject.keys)
    }
}
