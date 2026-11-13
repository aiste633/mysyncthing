package eu.stedb.mysyncthing.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/** One of Android's records of how the app's process ended, as far as the memory notice needs it. */
data class ProcessExit(val reason: Int, val description: String?, val timestamp: Long, val rssKb: Long)

/**
 * Android 17 ends processes that use more memory than its limit for the phone's RAM. Such an end is
 * recorded as REASON_OTHER with "MemoryLimiter" in the description (developer.android.com, Android 17
 * behaviour changes, "App memory limits"). The sticky service then starts again by itself, so without a
 * notice nobody would know.
 */
internal fun ProcessExit.byMemoryLimit(): Boolean =
    reason == ApplicationExitInfo.REASON_OTHER && description?.contains("MemoryLimiter") == true

/** The newest end by the memory limit after [seenUntil] (ms since the epoch), if any. */
internal fun List<ProcessExit>.newestMemoryLimitEnd(seenUntil: Long): ProcessExit? =
    filter { it.timestamp > seenUntil && it.byMemoryLimit() }.maxByOrNull { it.timestamp }

/**
 * Shows the memory notice once for an end by the memory limit not seen before. The first time (nothing
 * recorded yet, as after an update from 0.3.0) it only records now, so no old end is reported as new.
 */
internal suspend fun reportMemoryLimitEnd(context: Context, settings: Settings) {
    val seenUntil = settings.memoryExitsSeenUntil()
    val now = System.currentTimeMillis()
    if (seenUntil == null) {
        settings.setMemoryExitsSeenUntil(now)
        return
    }
    val exits = runCatching {
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
            .map { ProcessExit(it.reason, it.description, it.timestamp, it.rss) }
    }.getOrDefault(emptyList())
    val end = exits.newestMemoryLimitEnd(seenUntil) ?: return
    ProblemNotifications.memoryLimit(context, end)
    settings.setMemoryExitsSeenUntil(now)
}

/** Android keeps a few records per app; the newest are enough. */
private const val MAX_EXITS = 16
