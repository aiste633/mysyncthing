package eu.stedb.mysyncthing.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

const val MINUTES_PER_DAY = 24 * 60
const val MAX_SYNC_WINDOWS = 8

/** [scheduleError] when the schedule is on without a window: the one check that belongs to no window. */
const val NO_WINDOW = "Add a time window"

/**
 * A time of day the core may sync in, by the phone's clock: from [start] to
 * [end], in minutes after midnight. An [end] before [start] ends the next
 * day. [days] are ISO weekdays, 1 (Monday) to 7, on which the window starts.
 * Same rules as go/bridge syncWindow.check.
 */
@Serializable
data class SyncWindow(val start: Int, val end: Int, val days: Set<Int>) {
    companion object {
        /** A new window: the night, every day. */
        val NIGHT = SyncWindow(22 * 60, 7 * 60, (1..7).toSet())
    }
}

/** Why [w] can't be stored, or null. */
fun syncWindowError(w: SyncWindow): String? = when {
    w.start !in 0 until MINUTES_PER_DAY || w.end !in 0 until MINUTES_PER_DAY -> "Times from 00:00 to 23:59"
    w.start == w.end -> "Start and end must differ"
    w.days.isEmpty() -> "Pick at least one day"
    w.days.any { it !in 1..7 } -> "Days are 1 (Monday) to 7"
    else -> null
}

/** Why the schedule can't be stored, or null. */
fun scheduleError(on: Boolean, windows: List<SyncWindow>): String? = when {
    windows.size > MAX_SYNC_WINDOWS -> "At most $MAX_SYNC_WINDOWS time windows"
    on && windows.isEmpty() -> NO_WINDOW
    else -> windows.firstNotNullOfOrNull(::syncWindowError)
}

private fun minuteOfDay(t: LocalDateTime) = t.hour * 60 + t.minute

/** Whether [t] falls in this window. */
fun SyncWindow.isOpen(t: LocalDateTime): Boolean {
    val m = minuteOfDay(t)
    val day = t.dayOfWeek.value
    return if (start < end) day in days && m >= start && m < end
    else (day in days && m >= start) || (t.dayOfWeek.minus(1).value in days && m < end)
}

fun List<SyncWindow>.isOpen(t: LocalDateTime): Boolean = any { it.isOpen(t) }

/** Every start (and with [ends], every end) of these windows from the day before [t] to a week after. */
private fun List<SyncWindow>.edges(t: LocalDateTime, ends: Boolean): Sequence<LocalDateTime> {
    val midnight = t.truncatedTo(ChronoUnit.DAYS)
    return asSequence().flatMap { w ->
        (-1..7).asSequence().mapNotNull { k ->
            val day = midnight.plusDays(k.toLong())
            if (day.dayOfWeek.value !in w.days) return@mapNotNull null
            val start = day.plusMinutes(w.start.toLong())
            val end = (if (w.end < w.start) day.plusDays(1) else day).plusMinutes(w.end.toLong())
            if (ends) sequenceOf(start, end) else sequenceOf(start)
        }.flatten()
    }
}

/** The first minute after [t] at which a window starts or ends, or null without windows. */
fun List<SyncWindow>.nextChange(t: LocalDateTime): LocalDateTime? {
    val now = t.truncatedTo(ChronoUnit.MINUTES)
    return edges(t, ends = true).filter { it > now }.minOrNull()
}

/** When the next window opens after [t], or null without windows. */
fun List<SyncWindow>.nextOpen(t: LocalDateTime): LocalDateTime? {
    val now = t.truncatedTo(ChronoUnit.MINUTES)
    return edges(t, ends = false).filter { it > now }.minOrNull()
}

fun timeText(minutes: Int): String = "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)

/** "Outside sync times · next 22:00", with the weekday when the next window opens on another day. */
fun outsideText(windows: List<SyncWindow>, now: LocalDateTime): String {
    val next = windows.nextOpen(now) ?: return "Outside sync times"
    val day = if (next.toLocalDate() == now.toLocalDate()) "" else shortDay(next.dayOfWeek) + " "
    return "Outside sync times · next $day${timeText(minuteOfDay(next))}"
}

fun shortDay(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

/**
 * The current time, again at each start and end of [windows], at midnight
 * and when the clock or time zone changes. While the phone is idle Android may deliver
 * the alarm a few minutes late; the core then starts or pauses that late.
 */
fun scheduleClock(context: Context, windows: List<SyncWindow>): Flow<LocalDateTime> = callbackFlow {
    val alarms = context.getSystemService(AlarmManager::class.java)
    val tick = PendingIntent.getBroadcast(
        context, 0, Intent(ACTION_SCHEDULE_TICK).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    fun now() {
        val t = LocalDateTime.now()
        trySend(t)
        // Also at midnight, when "next Tue 22:00" becomes "next 22:00".
        val next = listOfNotNull(windows.nextChange(t), t.toLocalDate().plusDays(1).atStartOfDay()).min()
        // Inexact alarms need no permission; a change late by minutes is fine.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), tick)
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = now()
    }
    val filter = IntentFilter().apply {
        addAction(ACTION_SCHEDULE_TICK)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    now()
    awaitClose {
        context.unregisterReceiver(receiver)
        alarms.cancel(tick)
    }
}

private const val ACTION_SCHEDULE_TICK = "eu.stedb.mysyncthing.SCHEDULE_TICK"
