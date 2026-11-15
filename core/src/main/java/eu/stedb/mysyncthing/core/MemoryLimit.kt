package eu.stedb.mysyncthing.core

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Bundle

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

/**
 * Android 17's memory limit for a process the user does not see, as a foreground service runs, by the phone's
 * memory (MemTotal), both in MiB; null where Android sets none (source.android.com, "Memory Limiter",
 * memory-limiter-config.xml). Apps cannot read their limit, so the app takes it from this table.
 */
internal fun serviceLimitMib(totalMib: Long): Long? = when (totalMib) {
    in 3200 until 4800 -> 1024
    in 4800 until 6800 -> 2048
    in 6800 until 9216 -> 3072
    in 9216 until 14336 -> 4096
    in 14336 until 18432 -> 5120
    else -> null
}

/** What the core memory limit can be: the automatic choice, none, or the user's own in MB (10^6 bytes). */
object CoreMemoryLimit {
    /** Room left under Android's limit for what the core's limit does not cover: SQLite, the app's UI, Android's runtime. */
    const val MARGIN_MB = 300L

    /** The lowest own limit; 0 is no limit (go/bridge MinMemoryLimit). */
    const val MIN_MB = 256L

    /** Digits an own limit may have. */
    const val MAX_DIGITS = 6

    private const val MB = 1_000_000L
    private const val MIB = 1L shl 20

    /** Android's limit for this phone in MB, or null where it sets none (before Android 17, or the phone's memory). */
    fun androidLimitMb(sdk: Int, totalBytes: Long): Long? =
        if (sdk < ANDROID_17) null else serviceLimitMib(totalBytes / MIB)?.let { it * MIB / MB }

    /** The automatic limit in MB: Android's less [MARGIN_MB], at least [MIN_MB]; 0 (no limit) where Android sets none. */
    fun automaticMb(sdk: Int, totalBytes: Long): Long =
        androidLimitMb(sdk, totalBytes)?.let { maxOf(MIN_MB, it - MARGIN_MB) } ?: 0

    /** The highest own limit in MB: the phone's memory. */
    fun maxMb(totalBytes: Long): Long = totalBytes / MB

    /** Whether [mb] is an own limit the bridge takes: 0, or [MIN_MB] up to [maxMb]. */
    fun valid(mb: Long, totalBytes: Long): Boolean = mb == 0L || mb in MIN_MB..maxMb(totalBytes)

    /** The limit in force, in MB: the user's [own] one, or the automatic one when null. 0 is no limit. */
    fun effectiveMb(own: Long?, sdk: Int, totalBytes: Long): Long = own ?: automaticMb(sdk, totalBytes)

    fun bytes(mb: Long): Long = mb * MB

    private const val ANDROID_17 = 37
}

/** The phone's memory in bytes, as Android reports it (MemTotal). */
fun totalMemoryBytes(context: Context): Long =
    ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem

/**
 * The process's memory that Android 17's limit counts: anonymous memory in RAM plus what is swapped out
 * ("MemoryLimiter:AnonSwap"), in bytes, from /proc/self/status; null when it cannot be read.
 */
fun processAnonSwapBytes(): Long? = runCatching { anonSwapBytes(java.io.File("/proc/self/status").readText()) }.getOrNull()

/** RssAnon + VmSwap of a /proc/<pid>/status text, in bytes; null when either is missing. */
internal fun anonSwapBytes(status: String): Long? {
    fun kb(key: String) = status.lineSequence().firstOrNull { it.startsWith("$key:") }
        ?.removePrefix("$key:")?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull()
    val anon = kb("RssAnon") ?: return null
    val swap = kb("VmSwap") ?: return null
    return (anon + swap) * 1024
}

/**
 * Calls [onGone] when the app's last screen has left the front: the moment to give the memory the core
 * freed back to Android. Android's own signal for it, `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)`, is held
 * back until the process is in the background, which it never is while the foreground service runs
 * (Android 16 and 17: `pendingUiClean=true` for as long as it runs), so the service watches the screens itself.
 * Main thread only, as Android calls it.
 */
internal class ScreensGone(private val onGone: () -> Unit) : Application.ActivityLifecycleCallbacks {
    private val inFront = HashSet<Any>()

    fun started(screen: Any) {
        inFront += screen
    }

    /**
     * [recreating]: stopped for a new theme, language or font size, with its successor starting at once.
     * A screen that started before the service did is not in the set; its stop counts all the same.
     */
    fun stopped(screen: Any, recreating: Boolean) {
        inFront -= screen
        if (inFront.isEmpty() && !recreating) onGone()
    }

    override fun onActivityStarted(activity: Activity) = started(activity)
    override fun onActivityStopped(activity: Activity) = stopped(activity, activity.isChangingConfigurations)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
