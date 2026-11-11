package eu.stedb.mysyncthing.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Every input has a length limit (docs/input-validation.md, column Length):
 * no text field outside TextInput, which takes one, the bridge's limits are
 * the same numbers, and each check takes its limit and refuses one more.
 */
class InputLimitTest {
    private val sources = listOf(File("src/main"), File("../core/src/main")).flatMap { root ->
        root.walkTopDown().filter { it.extension == "kt" || it.extension == "xml" }.toList()
    }

    @Test
    fun everyTextFieldIsATextInput() {
        assertTrue("no sources found", sources.size > 20)
        val field = Regex("""\b(Outlined|Basic)?(Secure)?TextField\s*\(|<EditText|\bSearchBar\s*\(""")
        val outside = sources.filter { it.name != "TextInput.kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, line) -> field.containsMatchIn(line) }.map { (i, line) -> "${f.path}:${i + 1}: ${line.trim()}" }
        }
        assertEquals("text fields outside TextInput.kt (use TextInput, which takes a length limit)", emptyList<String>(), outside)
    }

    /** go/bridge/limits.go holds the same numbers for the web GUI and imports. */
    @Test
    fun bridgeHasTheSameLimits() {
        val go = File("../go/bridge/limits.go").readText()
        fun goConst(name: String) = Regex("""\b$name = ([0-9_]+)""").find(go)!!.groupValues[1].replace("_", "").toInt()
        assertEquals(goConst("maxText"), Limits.TEXT.n)
        assertEquals(goConst("maxFileName"), Limits.FILE_NAME.n)
        assertEquals(goConst("maxPath"), Limits.PATH.n)
        assertEquals(goConst("maxEntries"), Limits.LIST_ENTRIES)
        assertEquals(goConst("maxListenTotal"), Limits.LISTEN_TOTAL.n)
        assertEquals(goConst("maxIgnores"), Limits.IGNORES.n)
    }

    /** Characters are code points (as Go counts runes), bytes are UTF-8 as Go receives them. */
    @Test
    fun lengthCounts() {
        assertEquals(1, lengthOf("😀", Limits.TEXT))
        assertEquals(4, lengthOf("😀", Limits.FILE_NAME))
        assertEquals(3, lengthOf("\uD800", Limits.FILE_NAME)) // arrives as U+FFFD
        assertNull(lengthError("😀".repeat(1024), Limits.TEXT))
        assertEquals("At most 1,024 characters", lengthError("😀".repeat(1025), Limits.TEXT).en())
        assertEquals("At most 255 bytes", lengthError("é".repeat(128), Limits.FILE_NAME).en())
    }

    @Test
    fun lengthErrorProperty() {
        val r = Random(7)
        val alphabet = "aé€😀\uD800"
        repeat(20_000) {
            val limit = Limit(r.nextInt(0, 40), bytes = r.nextBoolean())
            val s = buildString { repeat(r.nextInt(0, 45)) { val c = alphabet.codePoints().toArray().random(r); appendCodePoint(c) } }
            val n = if (limit.bytes) s.codePoints().toArray().sumOf { if (it in 0xD800..0xDFFF) 3 else String(Character.toChars(it)).toByteArray().size } else s.codePointCount(0, s.length)
            assertEquals("$s in $limit", n <= limit.n, lengthError(s, limit) == null)
        }
    }

    private fun atAndOver(name: String, limit: Int, valid: (Int) -> String, check: (String) -> Any?) {
        assertNull("$name at $limit", check(valid(limit)))
        assertNotNull("$name at ${limit + 1}", check(valid(limit + 1)))
    }

    @Test
    fun eachCheckTakesItsLimit() {
        val text = { n: Int -> "aé€😀".repeat(n / 4) + "a".repeat(n % 4) }
        atAndOver("name", 1024, text) { textError(it).en() }
        atAndOver("required name", 1024, text) { requiredTextError(it).en() }
        atAndOver("folder ID", 1024, text) { folderIdError(it, emptyList()).en() }
        atAndOver("number", 32, { "0".repeat(it - 1) + "5" }) { wholeNumberError(it).en() }
        atAndOver("amount", 32, { "0".repeat(it - 1) + "5" }) { amountError(it, "kB/s").en() }
        atAndOver("port", 32, { "0".repeat(it - 4) + "1080" }) { proxyPortError(it).en() }
        atAndOver("proxy host", 253, { n -> ("a".repeat(63) + ".").repeat(n / 64) + "b".repeat(n % 64) }) { proxyHostError(it).en() }
        atAndOver("proxy user", 255, { "é".repeat(it / 2) + "a".repeat(it % 2) }) { proxyCredentialError(it).en() }
        atAndOver("export password", 1024, { "é".repeat(it / 2) + "a".repeat(it % 2) }) { exportPasswordError(it).en() }
        atAndOver("backup password", 1024, text) { backupPasswordError(it, wrong = false).en() }
        atAndOver("new folder", 255, { "é".repeat(it / 2) + "a".repeat(it % 2) }) { newFolderNameError(it, emptyList(), false).en() }
        atAndOver("address list", Limits.LIST_ENTRIES, { n -> List(n) { "tcp://:$it" }.joinToString("\n") }) { listError(it.lines(), null).en() }
        atAndOver("listen addresses together", 4096, { n -> List(4) { "tcp://" + "a".repeat(1000) }.joinToString("\n") + "\ntcp://" + "b".repeat(n - 4 * 1006 - 6) }) {
            listError(it.lines(), Limits.LISTEN_TOTAL).en()
        }
        assertNull(deviceIdError(" ".repeat(256 - Fixtures.server.length) + Fixtures.server).en())
        assertEquals("At most 256 characters", deviceIdError(" ".repeat(257)).en())
        // Keep free: up to 1 PB.
        assertNull(amountError("1000000000", "MB").en())
        assertNotNull(amountError("1000000001", "MB").en())
        assertNull(amountError("1000000", "GB").en())
        assertNotNull(amountError("1000000.1", "GB").en())
    }
}
