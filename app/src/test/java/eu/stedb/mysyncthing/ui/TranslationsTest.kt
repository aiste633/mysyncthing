package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.core.XmlStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each translation parses as aapt2 reads it (XmlStrings fails on an unescaped
 * apostrophe) and takes the same arguments as English, so String.format can't
 * crash or drop a value in that language.
 */
class TranslationsTest {
    /** values-it, values-pt-rBR, values-b+sr+Latn, as app/build.gradle.kts counts them. */
    private val language = Regex("""values-([a-z]{2,3}(-r[A-Z]{2})?|b\+[a-z]{2,3}(\+[A-Za-z0-9]+)*)""")
    private val folders = RES_DIRS.flatMap { d -> d.listFiles().orEmpty().map { it.name } }.filter(language::matches).distinct()

    private fun args(text: String) = PLACEHOLDER.findAll(text).map { it.value }.toSet()

    @Test
    fun italianIsThere() {
        assertTrue(folders.toString(), "values-it" in folders)
    }

    /** A plural form the language has and English lacks ("many") takes English "other"'s arguments; "one" may leave out the count. */
    @Test
    fun argumentsMatchEnglish() {
        for (folder in folders) {
            XmlStrings(RES_DIRS, R_CLASSES, folder = folder).all.forEach { (key, value) ->
                val plural = '/' in key
                val english = En.all[if (plural) key.substringBefore('/') + "/other" else key]
                assertNotNull("$folder: $key is not in English", english)
                if (plural && key.endsWith("/one")) assertTrue("$folder: $key", args(english!!).containsAll(args(value)))
                else assertEquals("$folder: $key", args(english!!), args(value))
            }
        }
    }
}
