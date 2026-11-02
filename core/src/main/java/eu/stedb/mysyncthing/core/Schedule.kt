package eu.stedb.mysyncthing.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

const val MINUTES_PER_DAY = 24 * 60
const val MAX_SYNC_WINDOWS = 8

/** [scheduleError] when the schedule is on without a window: the one check that belongs to no window. */
val NO_WINDOW = text(R.string.schedule_error_no_window)

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
fun syncWindowError(w: SyncWindow): UiText? = when {
    w.start !in 0 until MINUTES_PER_DAY || w.end !in 0 until MINUTES_PER_DAY -> text(R.string.schedule_error_times)
    w.start == w.end -> text(R.string.schedule_error_same_times)
    w.days.isEmpty() -> text(R.string.schedule_error_no_day)
    w.days.any { it !in 1..7 } -> text(R.string.schedule_error_days)
    else -> null
}

/** Why the schedule can't be stored, or null. */
fun scheduleError(on: Boolean, windows: List<SyncWindow>): UiText? = when {
    windows.size > MAX_SYNC_WINDOWS -> plural(R.plurals.schedule_error_too_many, MAX_SYNC_WINDOWS)
    on && windows.isEmpty() -> NO_WINDOW
    else -> windows.firstNotNullOfOrNull(::syncWindowError)
}

/** Minutes after midnight, as a [SyncWindow] counts them. */
fun minuteOfDay(t: LocalDateTime): Int = t.hour * 60 + t.minute

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

/** A time of day in minutes after midnight, as the phone's clock setting writes it: "22:00" or "10:00 PM". */
fun timeText(minutes: Int): UiText = time(LocalTime.of(minutes / 60, minutes % 60), "jm")

/** "Outside sync times · next 22:00", with the weekday when the next window opens on another day. */
fun outsideText(windows: List<SyncWindow>, now: LocalDateTime): UiText {
    val next = windows.nextOpen(now) ?: return text(R.string.schedule_outside)
    val time = timeText(minuteOfDay(next))
    return if (next.toLocalDate() == now.toLocalDate()) text(R.string.schedule_outside_next, time)
    else text(R.string.schedule_outside_next_day, shortDay(next.dayOfWeek), time)
}

/** "Mon", in the language. */
fun shortDay(d: DayOfWeek): UiText = time(d, "EEE")

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

/**
 * Returns once the wall clock reaches [at], or when the phone next wakes after that: the alarm
 * never wakes it, as nobody looks at a sleeping phone's status. Inexact, so no permission: Android
 * may deliver it up to 75 % of the wait late (seen on the emulator), which is fine here (user).
 */
suspend fun wallClockAt(context: Context, at: Instant) = suspendCancellableCoroutine { done ->
    val alarms = context.getSystemService(AlarmManager::class.java)
    val listener = AlarmManager.OnAlarmListener { done.resume(Unit) }
    alarms.set(AlarmManager.RTC, at.toEpochMilli(), "mysyncthing:status", listener, null)
    done.invokeOnCancellation { alarms.cancel(listener) }
}

private const val ACTION_SCHEDULE_TICK = "eu.stedb.mysyncthing.SCHEDULE_TICK"

const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY

/** Shorter stretches of syncing time may pass without any sync: the alarm can come that late (user: 1 h). */
const val SHORT_STRETCH_MINUTES = 60

/** Monday 00:00 is 0; the phone's clock, as the windows are. */
fun minuteOfWeek(t: LocalDateTime): Int = (t.dayOfWeek.value - 1) * MINUTES_PER_DAY + minuteOfDay(t)

/**
 * A stretch of the week in which syncing is allowed: from minute [start] of the week (see
 * [minuteOfWeek]) for [length] minutes, running past Sunday night into Monday.
 */
data class Stretch(val start: Int, val length: Int) {
    val end: Int get() = (start + length) % MINUTES_PER_WEEK
}

/**
 * The minutes of a week in which syncing is allowed. The windows repeat every week, minute by
 * minute, so this is exact: whether two schedules ever overlap, and for how long at a time, is
 * a question about two sets of 10,080 minutes. Only a day with a clock change differs (the hour
 * skipped in spring never comes).
 */
class WeekMask private constructor(private val open: BooleanArray) {
    val isEmpty: Boolean get() = open.none { it }
    val minutes: Int get() = open.count { it }

    fun isOpen(t: LocalDateTime): Boolean = open[minuteOfWeek(t)]

    infix fun and(other: WeekMask) = WeekMask(BooleanArray(MINUTES_PER_WEEK) { open[it] && other.open[it] })

    /** The first minute after [t] in which syncing is allowed, or null when it never is. */
    fun nextOpen(t: LocalDateTime): LocalDateTime? {
        val now = t.truncatedTo(ChronoUnit.MINUTES)
        val m = minuteOfWeek(now)
        val ahead = (1..MINUTES_PER_WEEK).firstOrNull { open[(m + it) % MINUTES_PER_WEEK] } ?: return null
        return now.plusMinutes(ahead.toLong())
    }

    /** The stretches of allowed time, the earliest in the week first; one across Sunday midnight counts once. */
    fun stretches(): List<Stretch> {
        if (open.all { it }) return listOf(Stretch(0, MINUTES_PER_WEEK))
        // Start right after a closed minute, so a stretch across Sunday midnight is not cut in two.
        val first = open.indexOfFirst { !it }
        val result = mutableListOf<Stretch>()
        var startAt = -1
        for (k in 1..MINUTES_PER_WEEK) {
            val m = (first + k) % MINUTES_PER_WEEK
            if (open[m] && startAt < 0) startAt = m
            if (!open[m] && startAt >= 0) {
                result += Stretch(startAt, (m - startAt + MINUTES_PER_WEEK) % MINUTES_PER_WEEK)
                startAt = -1
            }
        }
        return result.sortedBy { it.start }
    }

    /** Stretches too short to rely on ([SHORT_STRETCH_MINUTES]). */
    fun shortStretches(): List<Stretch> = stretches().filter { it.length < SHORT_STRETCH_MINUTES }

    override fun equals(other: Any?) = other is WeekMask && open.contentEquals(other.open)
    override fun hashCode() = open.contentHashCode()

    companion object {
        val ALWAYS = WeekMask(BooleanArray(MINUTES_PER_WEEK) { true })

        /** The minutes [windows] allow; as [List.isOpen] answers for every minute of the week. */
        fun of(windows: List<SyncWindow>): WeekMask {
            val open = BooleanArray(MINUTES_PER_WEEK)
            for (w in windows) for (day in w.days) {
                val from = (day - 1) * MINUTES_PER_DAY + w.start
                val length = if (w.end > w.start) w.end - w.start else MINUTES_PER_DAY - w.start + w.end
                for (k in 0 until length) open[(from + k) % MINUTES_PER_WEEK] = true
            }
            return WeekMask(open)
        }
    }
}

/** The minutes the global schedule allows: all of them while it is off. */
fun RunConditions.globalMask(): WeekMask = if (timeSchedule) WeekMask.of(windows) else WeekMask.ALWAYS

/**
 * When device [id] may sync by the clock: the global sync windows narrowed by its own. A device
 * without its own windows follows the global ones.
 */
fun RunConditions.mask(id: String): WeekMask {
    val own = deviceWindows[id]?.takeIf { it.on } ?: return globalMask()
    return globalMask() and WeekMask.of(own.windows)
}

/** The devices whose own windows never overlap the global ones: they would never sync. */
fun RunConditions.neverSyncing(): Set<String> =
    deviceWindows.filter { (_, w) -> w.on }.keys.filterTo(LinkedHashSet()) { mask(it).isEmpty }
