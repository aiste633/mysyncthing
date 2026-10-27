package eu.stedb.mysyncthing.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The English strings parse as aapt2 reads them (En fails on an unescaped apostrophe or a name used twice). */
class StringsTest {
    private val placeholder = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")

    /** Every form takes the arguments of "other"; English "one" may leave out the count ("Every day"). */
    @Test
    fun pluralFormsTakeTheSameArguments() {
        En.all.keys.filter { '/' in it }.groupBy { it.substringBefore('/') }.forEach { (name, forms) ->
            val args = forms.associate { f -> f.substringAfter('/') to placeholder.findAll(En.all.getValue(f)).map { it.value }.toSet() }
            val other = args.getValue("other")
            args.forEach { (quantity, a) ->
                if (quantity == "one") assertTrue("plurals $name/one", other.containsAll(a)) else assertEquals("plurals $name/$quantity", other, a)
            }
        }
    }

    /** With more than one argument, each is numbered, so a translation can reorder them. */
    @Test
    fun severalArgumentsAreNumbered() {
        En.all.forEach { (name, value) ->
            val args = placeholder.findAll(value).map { it.value }.filter { it != "%%" }.toList()
            if (args.size > 1) assertTrue("$name: $value", args.all { '$' in it })
        }
    }
}
