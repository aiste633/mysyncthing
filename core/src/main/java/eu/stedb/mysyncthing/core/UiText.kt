package eu.stedb.mysyncthing.core

import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAccessor
import java.util.Date
import java.util.Locale

/**
 * Words to show, kept as a string resource and its arguments until they are shown.
 * Logic thus stays testable on the JVM, and the words follow a change of language.
 * Arguments may be [UiText]s themselves; [Raw] carries text that is not ours to
 * translate, such as names, paths and the core's own messages. Numbers and times
 * ([Num], [Time]) are written as the phone's language and clock setting write them.
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any>) : UiText
    data class Raw(val text: String) : UiText
    data class Joined(val parts: List<UiText>, val separator: String) : UiText

    /** A number with [minFraction] to [maxFraction] decimals, grouped as the language groups digits. */
    data class Num(val value: Double, val minFraction: Int = 0, val maxFraction: Int = minFraction) : UiText

    /**
     * A [ZonedDateTime], [LocalTime] or [DayOfWeek] by a CLDR [skeleton] such as "dMMMy" or
     * "EEE": the language picks the order and punctuation. In the skeleton "j" is the hour,
     * 24 h or 12 h as the phone is set.
     */
    data class Time(val value: TemporalAccessor, val skeleton: String) : UiText
}

fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** [count] picks the plural form; it is also the only argument unless [args] are given. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

fun raw(text: String): UiText = UiText.Raw(text)

fun num(n: Long): UiText = UiText.Num(n.toDouble())

fun num(n: Int): UiText = num(n.toLong())

fun time(value: TemporalAccessor, skeleton: String): UiText = UiText.Time(value, skeleton)

/** The parts in a row, such as a list after "On ". The separator is punctuation, the same in every language we show. */
fun List<UiText>.joined(separator: String): UiText = UiText.Joined(this, separator)

/** Where the words come from: the app's resources, or in unit tests the XML files themselves. */
interface Strings {
    val locale: Locale
    /** The phone's clock setting: "j" in a [UiText.Time] skeleton becomes "H" or "h". */
    val is24Hour: Boolean
    fun string(@StringRes id: Int): String
    fun quantity(@PluralsRes id: Int, count: Int): String
    /** [value] by a CLDR skeleton without "j", such as "dMMMHm". */
    fun time(value: TemporalAccessor, skeleton: String): String
}

fun UiText.resolve(strings: Strings): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> strings.string(id).formatted(strings, args)
    is UiText.Plural -> strings.quantity(id, count).formatted(strings, args)
    is UiText.Joined -> parts.joinToString(separator) { it.resolve(strings) }
    is UiText.Num -> NumberFormat.getNumberInstance(strings.locale).apply {
        minimumFractionDigits = minFraction
        maximumFractionDigits = maxFraction
        roundingMode = RoundingMode.HALF_UP // as String.format
    }.format(value)
    is UiText.Time -> strings.time(value, skeleton.replace("j", if (strings.is24Hour) "H" else "h"))
}

/** As Resources.getString(id, args): formatted only with arguments, so a "%" in a plain string stays. */
private fun String.formatted(strings: Strings, args: List<Any>): String =
    if (args.isEmpty()) this
    else String.format(strings.locale, this, *args.map { if (it is UiText) it.resolve(strings) else it }.toTypedArray())

/**
 * The app's words in [resources]' language, with the clock setting of the phone. Dates and
 * numbers follow the phone's region too: English on a US phone writes "Oct 3, 2025".
 */
fun Context.strings(resources: Resources = this.resources): Strings = object : Strings {
    override val locale: Locale = resources.configuration.locales[0]
    override val is24Hour: Boolean get() = DateFormat.is24HourFormat(this@strings)
    override fun string(id: Int) = resources.getString(id)
    override fun quantity(id: Int, count: Int) = resources.getQuantityString(id, count)
    override fun time(value: TemporalAccessor, skeleton: String): String {
        val t = value.zoned()
        return android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale)
            .apply { timeZone = android.icu.util.TimeZone.getTimeZone(t.zone.id) }
            .format(Date.from(t.toInstant()))
    }
}

/** A moment that shows [this]: a time of day or a weekday in UTC's first week of 2001 (no clock change; 1 January was a Monday). */
private fun TemporalAccessor.zoned(): ZonedDateTime = when (this) {
    is ZonedDateTime -> this
    is LocalTime -> atDate(MONDAY).atZone(ZoneOffset.UTC)
    is DayOfWeek -> MONDAY.plusDays(value - 1L).atStartOfDay(ZoneOffset.UTC)
    else -> error("no time for $this")
}

private val MONDAY = LocalDate.of(2001, 1, 1)

fun UiText.resolve(context: Context): String = resolve(context.strings())

/** An exception whose message is ours, so people read it in their language. */
class UiTextException(val text: UiText) : Exception()

/** What went wrong, for people: our own words, else the message as it came (the core's are English). */
fun Throwable.messageText(): UiText = (this as? UiTextException)?.text ?: raw(message ?: toString())
