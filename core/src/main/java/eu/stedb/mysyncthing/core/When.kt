package eu.stedb.mysyncthing.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * A REST time stamp (RFC 3339) as an instant. Null for an unreadable one and for
 * Go's zero time (year 1), which the core uses for "never".
 */
fun instantOf(rfc3339: String): Instant? =
    runCatching { OffsetDateTime.parse(rfc3339) }.getOrNull()?.takeIf { it.year >= 2000 }?.toInstant()

/**
 * A time for people, in the language's order and the phone's clock setting:
 * "12:03" today, "yesterday, 22:10", "25 Oct, 12:03" this year, else with the year.
 */
fun whenText(at: Instant, now: ZonedDateTime = ZonedDateTime.now()): UiText {
    val t = at.atZone(now.zone)
    val day = t.toLocalDate()
    return when {
        day == now.toLocalDate() -> time(t, "jm")
        day == now.toLocalDate().minusDays(1) -> text(R.string.when_yesterday, time(t, "jm"))
        t.year == now.year -> time(t, "dMMMjm")
        else -> time(t, "dMMMyjm")
    }
}

/** [whenText] for a REST time stamp; null for "never" and unreadable ones. */
fun whenText(rfc3339: String, now: ZonedDateTime = ZonedDateTime.now()): UiText? = instantOf(rfc3339)?.let { whenText(it, now) }
