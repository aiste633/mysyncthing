package eu.stedb.mysyncthing.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Folder and device IDs go into REST paths. Any ID the app allows (no "/" or
 * control character, not "." or "..") must arrive as exactly the one segment
 * it is (OkHttp drops tabs and line breaks from URLs); the core decodes
 * it back (go/bridge TestFolderIDsInRESTPaths). The path used to be built by
 * string concatenation, so "a\b" became two segments and "?" cut the path.
 */
class RestClientPathTest {
    private val client = RestClient("127.0.0.1:8384", "key")

    private fun segments(id: String) = client.request("rest/config/folders", emptyList(), id).build().url.pathSegments

    @Test
    fun anyAllowedIdIsOneSegment() {
        // Whole code points: IDs come from the core as valid UTF-8, never half an emoji.
        val pool = "aZ09 ._-\\:?#%&@\$!*'\"[]{};^<>|~+=\u0000\n\téß€".map { it.toString() } + "😀"
        val r = Random(20261026)
        val ids = List(20_000) { (1..r.nextInt(1, 20)).joinToString("") { pool.random(r) } } +
            listOf("abcde-12345", "My photos", "a%b", "a?b#c", "a\\b", "%2F", "%2e%2e", "...", " . ")
        for (id in ids) {
            if ('/' in id || id == "." || id == ".." || id.any { it.isISOControl() }) continue
            assertEquals(id, listOf("rest", "config", "folders", id), segments(id))
        }
    }
}
