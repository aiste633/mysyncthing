package eu.stedb.mysyncthing.ui

import kotlin.random.Random

/** Makes one test input. */
typealias Gen<T> = Random.() -> T

/**
 * Checks [property] on [runs] inputs from [gen]. The seed is fixed, so a
 * failure repeats, and its message shows the input. Hand-rolled on purpose:
 * a property library would be a new dependency to verify and keep reproducible.
 */
fun <T> forAll(gen: Gen<T>, runs: Int = 20_000, seed: Long = 20261026, property: (T) -> Unit) {
    val r = Random(seed)
    repeat(runs) {
        val input = r.gen()
        try {
            property(input)
        } catch (e: Throwable) {
            throw AssertionError("fails for ${show(input)}: ${e.message}", e)
        }
    }
}

private fun show(v: Any?): String = when (v) {
    is String -> "\"" + v.flatMap { c -> if (c.isISOControl() || c.isSurrogate()) "\\u%04x".format(c.code).toList() else listOf(c) }.joinToString("") + "\""
    is Pair<*, *> -> "(${show(v.first)}, ${show(v.second)})"
    is Collection<*> -> v.joinToString(prefix = "[", postfix = "]") { show(it) }
    else -> v.toString()
}

fun <T> oneOf(vararg gens: Gen<T>): Gen<T> = { gens.random(this).invoke(this) }

fun <T> pick(vararg values: T): Gen<T> = { values.random(this) }

fun Random.chars(alphabet: String, min: Int, max: Int): String =
    (1..nextInt(min, max + 1)).map { alphabet.random(this) }.joinToString("")

/** Digits of the scripts phone keyboards type: ASCII, Arabic-Indic, Persian, Devanagari, full-width. */
val DIGIT_SETS = listOf("0123456789", "٠١٢٣٤٥٦٧٨٩", "۰۱۲۳۴۵۶۷۸۹", "०१२३४५६७८९", "０１２３４５６７８９")

/** Text as people type or paste it into a number field. */
val typedNumber: Gen<String> = oneOf(
    { nextLong(0, 1_000_000).toString() },
    { nextInt(0, 100_000).toString() + pick(".", ",")(this) + nextInt(0, 1000) },
    { pick(" ", "", "\t")(this) + nextInt(0, 500) + pick(" ", "", "\n")(this) },
    { "0".repeat(nextInt(1, 4)) + nextInt(0, 99) },
    { nextInt(0, 100).toString().map { DIGIT_SETS[nextInt(DIGIT_SETS.size)][it - '0'] }.joinToString("") },
    { chars("0123456789", 10, 40) },
    { pick("-", "+")(this) + nextInt(0, 100) },
    pick(
        "", " ", ".", ",", "1.", ".5", ",5", "1,5", "1.5.2", "1,000.5", "1 000", "1e3", "1E-2", "0x10", "1d", "2f", "NaN",
        "Infinity", "-Infinity", "1e999", "١٢", "۳۰", "５", "½", "²", "Ⅻ", "12abc", "abc", "--1", "+-1", "9".repeat(400),
    ),
)

/** A mix of everything: control characters, other scripts, emoji, lone surrogates. */
val anyText: Gen<String> = {
    val pool = "aZ09 ._-/\\:?#%&@$!*'\"[]{};^<>|~\u0000\u0001\n\r\t\u007f ​﻿�éß€ÄИ中😀\ud800"
    chars(pool, 0, 30)
}
