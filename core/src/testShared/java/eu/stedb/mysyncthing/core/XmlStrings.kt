package eu.stedb.mysyncthing.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The English strings read straight from res/values (or a translation's [folder],
 * such as values-it, for checks of its text), for unit tests on the JVM,
 * where there are no Resources. Resource IDs are matched to names through the
 * generated R classes. Text is unescaped as aapt2 does for the cases our files
 * use; a value it would refuse (an unescaped apostrophe) fails here too.
 * Plurals follow English: "one" for 1, else "other". Dates, times and numbers follow
 * [locale] (en-GB by default) with a 24 h clock unless [is24Hour] is false: the JDK's CLDR
 * data, which gives the same text as Android 16's ICU (checked on the emulator, including
 * the narrow no-break space before "pm").
 */
class XmlStrings(
    private val dirs: List<File>,
    private val classes: List<Class<*>>,
    override val is24Hour: Boolean = true,
    override val locale: Locale = Locale.UK,
    private val folder: String = "values",
) : Strings {

    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        val files = dirs.flatMap { File(it, folder).listFiles { f -> f.extension == "xml" }.orEmpty().sorted() }
        check(files.isNotEmpty()) { "no $folder XML in $dirs (working directory ${File("").absolutePath})" }
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

    /** The skeleton as ICU takes it, in the order the JDK's requested template wants ("dMMMy" is "yMMMd"). */
    override fun time(value: TemporalAccessor, skeleton: String): String =
        (OF_LOCALIZED_PATTERN.invoke(null, skeleton.toList().sortedBy { TEMPLATE_ORDER.indexOf(it) }.joinToString("")) as DateTimeFormatter)
            .withLocale(locale).format(value)

    /** The same English strings with another clock setting, or dates and numbers as [locale] writes them. */
    fun with(is24Hour: Boolean = this.is24Hour, locale: Locale = this.locale) = XmlStrings(dirs, classes, is24Hour, locale, folder)

    /** Every string and plural form, for checks over all of them. */
    val all: Map<String, String> get() = strings + plurals.flatMap { (n, f) -> f.map { (q, v) -> "$n/$q" to v } }

    private companion object {
        /** Field order of [DateTimeFormatter.ofLocalizedPattern]'s requested template. */
        const val TEMPLATE_ORDER = "GyQMwEdBhHjCmsvz"

        /** JDK 19+; tests compile against android.jar, which lacks it, and run on JDK 21. */
        val OF_LOCALIZED_PATTERN: java.lang.reflect.Method = DateTimeFormatter::class.java.getMethod("ofLocalizedPattern", String::class.java)

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
