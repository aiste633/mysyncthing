package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.ProxySettings
import eu.stedb.mysyncthing.core.ProxyType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

class ProxyExportValidationTest {
    /** The bridge's own test (TestValidationContract) checks it against the same file. */
    private val contract = Json.parseToJsonElement(File("../go/bridge/testdata/validation-contract.json").readText()).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun proxyFieldsAgreeWithTheBridge() = contract.getValue("proxy").jsonArray.map { it.jsonObject }.forEach { c ->
        val ok = c.getValue("ok").jsonPrimitive.boolean
        assertEquals(c.toString(), ok, proxyError(c.str("scheme"), c.str("host"), c.getValue("port").jsonPrimitive.int, c.str("user"), c.str("password")).en() == null)
    }

    @Test
    fun exportPasswordAgreesWithTheBridge() = contract.getValue("exportPassword").jsonArray.map { it.jsonObject }.forEach { c ->
        assertEquals(c.str("password"), c.getValue("ok").jsonPrimitive.boolean, exportPasswordError(c.str("password")).en() == null)
    }

    // --- Generators: what people type or paste into the fields.

    private val typedHost: Gen<String> = oneOf(
        pick("127.0.0.1", "::1", "198.51.100.20", "203.0.113.1", "2001:db8::1", "2001:db8::7", "::192.0.2.1", "2001:db8::192.0.2.1", "localhost", "proxy.example.org"),
        { "${nextInt(0, 300)}.${nextInt(0, 300)}.${nextInt(0, 300)}.${nextInt(0, 300)}" },
        { (1..nextInt(1, 9)).joinToString(":") { nextInt(0, 0x20000).toString(16) } + pick("", "::", ":")(this) },
        { chars("abcdefghijklmnopqrstuvwxyz0123456789-", 1, 20) + pick("", ".example", ".example.org", ".", "..x", "-", " ")(this) },
        { pick(" ", "", "\t")(this) + pick("p", "203.0.113.1", "::1")(this) + pick(" ", "", "\n")(this) },
        { "[" + pick("::1", "2001:db8::1")(this) + "]" + pick("", ":" + nextInt(1, 65536))(this) },
        anyText,
    )

    private val typedPort: Gen<String> = oneOf(typedNumber, { nextInt(1, 65536).toString() }, pick("0", "65535", "65536", "1080", "9050", " 8080 "))

    @Test
    fun acceptedHostsMakeAProxyUrl() = forAll(typedHost) { h ->
        if (proxyHostError(h).en() != null) return@forAll
        assertTrue(h, h.isNotEmpty() && h.none { it.isWhitespace() || it in "/@[]%?#" })
        // java.net.URI brackets an IPv6 host itself and refuses what a URL can't carry.
        val u = URI("socks5", null, h, 1080, null, null, null)
        assertEquals(h, if (':' in h) "[$h]" else h, u.host)
        assertEquals(1080, u.port)
    }

    @Test
    fun portsAreWholeNumbersFrom1To65535() = forAll(typedPort) { s ->
        val ok = proxyPortError(s).en() == null
        val n = s.trim().takeIf { t -> t.isNotEmpty() && t.all { it in '0'..'9' } }?.toIntOrNull()
        assertEquals(s, n != null && n in 1..65535, ok)
        if (s.any { it.isDigit() && it !in '0'..'9' }) assertEquals("Use the digits 0-9", proxyPortError(s).en())
    }

    /** Keyboards in other scripts type their own digits; adb can't, so the emulator checks couldn't. */
    @Test
    fun otherScriptsDigitsAreRefused() {
        for (s in listOf("٣", "١٠٨٠", "۱۰۸۰", "१०८०", "１０８０", "10८0")) assertEquals(s, "Use the digits 0-9", proxyPortError(s).en())
        assertNull(proxyForm(ProxyType.Socks5, "127.0.0.1", "١٠٨٠", "", "", false))
        assertNotNull(proxyForm(ProxyType.Socks5, "127.0.0.1", "1080", "", "", false))
    }

    @Test
    fun credentialsFitSocks5() = forAll(oneOf(anyText, { chars("aü中😀", 0, 200) }, { "x".repeat(nextInt(250, 260)) })) { s ->
        val ok = proxyCredentialError(s).en() == null
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (ok) {
            assertTrue(s, bytes.size <= 255 && s.none { it.isISOControl() })
            assertEquals("no lone surrogates: $s", s, String(bytes, Charsets.UTF_8))
        } else {
            assertTrue(s, bytes.size > 255 || s.any { it.isISOControl() } || String(bytes, Charsets.UTF_8) != s)
        }
    }

    /** The form gives settings exactly when every field is valid, with the host trimmed; "No proxy" drops the rest. */
    private data class Form(val type: ProxyType, val host: String, val port: String, val user: String, val password: String)

    @Test
    fun formGivesValidSettingsOnly() = forAll({
        Form(ProxyType.entries.random(this), typedHost(this), typedPort(this), pick("", "me", "ü", "a\nb")(this), pick("", "pw", "x".repeat(300))(this))
    }) { (type, host, port, user, password) ->
        val p = proxyForm(type, host, port, user, password, only = true)
        if (type == ProxyType.None) {
            assertEquals(ProxySettings(), p)
            return@forAll
        }
        val fieldsOk = proxyHostError(host.trim()).en() == null && proxyPortError(port).en() == null &&
            proxyUserError(user, password).en() == null && proxyCredentialError(password).en() == null
        assertEquals(fieldsOk, p != null)
        if (p != null) {
            assertEquals(host.trim(), p.host)
            assertEquals(port.trim().toInt(), p.port)
            assertNull(proxyError(type.scheme, p.host, p.port, p.user, p.password).en())
        }
    }

    @Test
    fun exportPasswordRules() = forAll(oneOf(anyText, { chars("aZ9 -äß€п中😀", 0, 30) }, { "p".repeat(nextInt(1020, 1030)) })) { pw ->
        val ok = exportPasswordError(pw).en() == null
        val bytes = pw.toByteArray(Charsets.UTF_8)
        val expected = pw.isEmpty() || (
            String(bytes, Charsets.UTF_8) == pw && pw.none { it.isISOControl() } &&
                pw.codePointCount(0, pw.length) >= MIN_EXPORT_PASSWORD && bytes.size <= 1024
            )
        assertEquals(pw, expected, ok)
    }

    @Test
    fun exportPasswordMessages() {
        assertNull(exportPasswordError("").en())
        assertEquals("At least 12 characters", exportPasswordError("short").en())
        assertEquals("At least 12 characters", exportPasswordError("😀".repeat(11)).en()) // 22 UTF-16 units, 11 characters
        assertNull(exportPasswordError("😀".repeat(12)).en())
        assertEquals("No line breaks or control characters", exportPasswordError("twelve chars\nlong").en())
        assertEquals("No line breaks or control characters", exportPasswordError("twelve chars \ud800 long").en())
        assertNotNull(exportPasswordError("p".repeat(1025)).en())
        assertNull(exportRepeatError("same password", "same password").en())
        assertEquals("Not the same password", exportRepeatError("same password", "same passwort").en())
    }

    @Test
    fun hostMessages() {
        assertEquals("Required", proxyHostError("").en())
        assertEquals("Without brackets, for example ::1", proxyHostError("[::1]").en())
        assertEquals("An IP address or a host name", proxyHostError("192.0.2.999").en())
        assertNull(proxyHostError("2001:db8::192.0.2.1").en())
        assertEquals("Needed with a password", proxyUserError("", "pw").en())
        assertFalse(proxyPortError("").en() == null)
    }

    @Test
    fun proxyTexts() {
        assertEquals("None: direct connections", proxyText(ProxySettings()).en())
        assertEquals("SOCKS5 127.0.0.1:9050, nothing else", proxyText(ProxySettings.TOR).en())
        assertEquals("HTTP [::1]:3128, direct if it fails", proxyText(ProxySettings(ProxyType.Http, "::1", 3128)).en())
        // The password never shows up, not even in logs.
        assertFalse("secret" in ProxySettings(ProxyType.Socks5, "p", 1, "me", "secret").toString())
    }
}
