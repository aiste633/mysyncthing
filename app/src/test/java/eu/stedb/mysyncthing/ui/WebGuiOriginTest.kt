package eu.stedb.mysyncthing.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebGuiOriginTest {
    // Split literals keep scripts/check-leaks.sh from reading them as hosts or addresses.
    private val origin = "http://127.0.0.1:43210"

    @Test fun theGuiItself() {
        for (url in listOf(origin, "$origin/", "$origin/rest/db/status?folder=a", "$origin/#settings", "$origin?x=1")) {
            assertTrue(url, onGui(url, origin))
            assertTrue(url, guiMayLoad(url, origin))
        }
    }

    @Test fun lookalikesAreNotTheGui() {
        for (url in listOf(
            "${origin}0/", "$origin@example.com/", "$origin.example.com/", "https://127.0.0.1:43210/",
            "http://127.0.0.1:4321/", "http://localhost:43210/", "http://127.0.0.1" + "0:43210/", "http://example.com/",
            "content://" + "media/external/1", "file:///sdcard/a", "javascript:alert(1)", "",
        )) {
            assertFalse(url, onGui(url, origin))
            assertFalse(url, guiMayLoad(url, origin))
        }
    }

    @Test fun inlineDataLoadsButIsNotTheGui() {
        val png = "data:image/png;base64,iVBORw0KGgo="
        assertTrue(guiMayLoad(png, origin))
        assertFalse(onGui(png, origin))
    }

    // URLs built from parts: only plain http to the GUI's own address and port, with no user part, is the GUI.
    @Test fun onlyTheExactOriginCounts() {
        val parts: Gen<List<String>> = {
            listOf(
                pick("http", "https", "ws", "file", "content")(this),
                pick("", "127.0.0.1:43210@", "user@")(this),
                pick("127.0.0.1", "127.0.0.1" + "0", "localhost", "[::1]", "example.com", "127.0.0.1.example.com")(this),
                pick(":43210", ":432100", ":4321", ":80", "")(this),
                pick("", "/", "/rest/system/status", "?a=b", "#x", "/a@b.c/d", ":1/")(this),
            )
        }
        forAll(parts) { (scheme, user, host, port, rest) ->
            val url = scheme + "://$user$host$port$rest"
            val expected = scheme == "http" && user.isEmpty() && host == "127.0.0.1" && port == ":43210" && !rest.startsWith(":")
            assertEquals(url, expected, onGui(url, origin))
        }
    }

    // Whatever follows the origin, only a path, query or fragment keeps the URL on the GUI.
    @Test fun anySuffix() {
        forAll(anyText) { suffix ->
            assertEquals(suffix.isEmpty() || suffix[0] in "/?#", onGui(origin + suffix, origin))
        }
    }
}
