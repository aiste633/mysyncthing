package eu.stedb.mysyncthing.core

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Words to show, kept as a string resource and its arguments until they are shown.
 * Logic thus stays testable on the JVM, and the words follow a change of language.
 * Arguments may be [UiText]s themselves; [Raw] carries text that is not ours to
 * translate, such as names, paths and the core's own messages.
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any>) : UiText
    data class Raw(val text: String) : UiText
    data class Joined(val parts: List<UiText>, val separator: String) : UiText
}

fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** [count] picks the plural form; it is also the only argument unless [args] are given. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

fun raw(text: String): UiText = UiText.Raw(text)

/** The parts in a row, such as a list after "On ". The separator is punctuation, the same in every language we show. */
fun List<UiText>.joined(separator: String): UiText = UiText.Joined(this, separator)

/** Where the words come from: the app's resources, or in unit tests the XML files themselves. */
interface Strings {
    val locale: Locale
    fun string(@StringRes id: Int): String
    fun quantity(@PluralsRes id: Int, count: Int): String
}

fun UiText.resolve(strings: Strings): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> strings.string(id).formatted(strings, args)
    is UiText.Plural -> strings.quantity(id, count).formatted(strings, args)
    is UiText.Joined -> parts.joinToString(separator) { it.resolve(strings) }
}

/** As Resources.getString(id, args): formatted only with arguments, so a "%" in a plain string stays. */
private fun String.formatted(strings: Strings, args: List<Any>): String =
    if (args.isEmpty()) this
    else String.format(strings.locale, this, *args.map { if (it is UiText) it.resolve(strings) else it }.toTypedArray())

fun Resources.strings(): Strings = object : Strings {
    override val locale: Locale get() = configuration.locales[0]
    override fun string(id: Int) = getString(id)
    override fun quantity(id: Int, count: Int) = getQuantityString(id, count)
}

fun UiText.resolve(resources: Resources): String = resolve(resources.strings())

fun UiText.resolve(context: Context): String = resolve(context.resources)

/** An exception whose message is ours, so people read it in their language. */
class UiTextException(val text: UiText) : Exception()

/** What went wrong, for people: our own words, else the message as it came (the core's are English). */
fun Throwable.messageText(): UiText = (this as? UiTextException)?.text ?: raw(message ?: toString())
