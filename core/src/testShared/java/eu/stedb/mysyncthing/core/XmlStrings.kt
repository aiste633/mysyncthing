package eu.stedb.mysyncthing.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The English strings read straight from res/values, for unit tests on the JVM,
 * where there are no Resources. Resource IDs are matched to names through the
 * generated R classes. Text is unescaped as aapt2 does for the cases our files
 * use; a value it would refuse (an unescaped apostrophe) fails here too.
 * Plurals follow English: "one" for 1, else "other". Times use the patterns Android
 * gives en-GB for the skeletons the app uses, with a 24 h clock unless [is24Hour] is false;
 * numbers follow [locale].
 */
class XmlStrings(
    private val dirs: List<File>,
    private val classes: List<Class<*>>,
    override val is24Hour: Boolean = true,
    override val locale: Locale = Locale.UK,
) : Strings {

    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        val files = dirs.flatMap { File(it, "values").listFiles { f -> f.extension == "xml" }.orEmpty().sorted() }
        check(files.isNotEmpty()) { "no values XML in $dirs (working directory ${File("").absolutePath})" }
        for (file in files) {
            val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
            for (e in root.childElements()) {
                val name = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> check(strings.put(name, value(e)) == null) { "string $name twice (also in $file)" }
                    "plurals" -> check(plurals.put(name, e.childElements().associate { it.getAttribute("quantity") to value(it) }) == null) {
                        "plurals $name twice (also in $file)"
                    }
                }
            }
        }
    }

    private val stringNames = names(classes, "string")
    private val pluralNames = names(classes, "plurals")

    override fun string(id: Int): String {
        val name = stringNames[id] ?: error("no string resource $id")
        return strings[name] ?: error("string $name not in values XML")
    }

    override fun quantity(id: Int, count: Int): String {
        val name = pluralNames[id] ?: error("no plurals resource $id")
        val forms = plurals[name] ?: error("plurals $name not in values XML")
        return forms[if (count == 1) "one" else "other"] ?: forms.getValue("other")
    }

    override fun time(value: TemporalAccessor, skeleton: String): String =
        DateTimeFormatter.ofPattern(PATTERNS[skeleton] ?: error("no en-GB pattern for skeleton $skeleton"), Locale.UK).format(value)

    /** The same English strings with another clock setting, or numbers as [locale] writes them (times stay en-GB). */
    fun with(is24Hour: Boolean = this.is24Hour, locale: Locale = this.locale) = XmlStrings(dirs, classes, is24Hour, locale)

    /** Every string and plural form, for checks over all of them. */
    val all: Map<String, String> get() = strings + plurals.flatMap { (n, f) -> f.map { (q, v) -> "$n/$q" to v } }

    private companion object {
        /** DateFormat.getBestDateTimePattern(Locale.UK, skeleton) on Android 16. */
        val PATTERNS = mapOf(
            "Hm" to "HH:mm", "hm" to "h:mm a",
            "Hms" to "HH:mm:ss", "hms" to "h:mm:ss a",
            "dMMM" to "d MMM", "dMMMy" to "d MMM y",
            "dMMMHm" to "d MMM, HH:mm", "dMMMhm" to "d MMM, h:mm a",
            "dMMMyHm" to "d MMM y, HH:mm", "dMMMyhm" to "d MMM y, h:mm a",
            "EEE" to "EEE",
        )

        fun names(rClasses: List<Class<*>>, type: String): Map<Int, String> = rClasses
            .mapNotNull { r -> r.classes.find { it.simpleName == type } }
            .flatMap { c -> c.fields.map { it.getInt(null) to it.name } }
            .toMap()

        fun Element.childElements(): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

        /** The text content with markup such as <xliff:g> dropped, then quotes, whitespace and escapes as aapt2 reads them. */
        fun value(e: Element): String {
            val raw = buildString { e.appendText(this) }
            val out = StringBuilder()
            var quoted = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length -> {
                        i++
                        when (val n = raw[i]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'u' -> { out.append(raw.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                            else -> out.append(n)
                        }
                    }
                    c == '"' -> quoted = !quoted
                    c == '\'' && !quoted -> error("unescaped apostrophe in ${e.getAttribute("name")}: $raw")
                    c.isWhitespace() && !quoted -> if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    else -> out.append(c)
                }
                i++
            }
            return out.toString().trim()
        }

        fun Node.appendText(sb: StringBuilder) {
            for (k in 0 until childNodes.length) {
                val n = childNodes.item(k)
                if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) sb.append(n.nodeValue) else n.appendText(sb)
            }
        }
    }
}
