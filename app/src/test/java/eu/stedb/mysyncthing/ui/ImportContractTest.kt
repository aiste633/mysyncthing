package eu.stedb.mysyncthing.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A backup's config.xml goes through the bridge's import policy
 * (go/bridge/importpolicy.go), which ports the forms' rules. Its test
 * (TestImportPathAndAddressContract) reads the same cases.
 */
class ImportContractTest {
    private val contract = Json.parseToJsonElement(File("../go/bridge/testdata/validation-contract.json").readText()).jsonObject

    private fun cases(key: String) = contract.getValue(key).jsonArray.map { it.jsonObject }.also { assertTrue(key, it.isNotEmpty()) }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private val JsonObject.ok get() = getValue("ok").jsonPrimitive.boolean

    /** The same folders are refused in the forms and in an import. */
    @Test
    fun folderPathsAgreeWithTheBridge() = cases("folderPath").forEach { c ->
        val others = c.getValue("others").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(c.toString(), c.ok, folderPathError(c.str("path"), others).en() == null)
    }

    /**
     * Every address an import keeps can be edited in the forms. The import is
     * stricter: relays name a server of the backup's choosing.
     */
    @Test
    fun importedAddressesAreValidInTheForms() = cases("importAddress").filter { it.ok }.forEach { c ->
        val a = c.str("address")
        assertTrue(c.toString(), if (c.str("kind") == "listen") isListenAddress(a) else isDeviceAddress(a))
    }
}
