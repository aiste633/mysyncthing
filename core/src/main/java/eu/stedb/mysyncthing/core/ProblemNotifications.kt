package eu.stedb.mysyncthing.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** A folder that stopped syncing: the core puts it in state "error" (marker missing, path gone, below "keep free"). */
data class FolderProblem(val id: String, val name: String, val error: String)

/** Active folders in error. Single files that fail don't count: they are often temporary and retried. */
fun Snapshot.folderProblems(): List<FolderProblem> = folders.filter { !it.paused }.mapNotNull { f ->
    folderStatus[f.id]?.takeIf { it.state == "error" }?.let { FolderProblem(f.id, f.label.ifEmpty { f.id }, it.error) }
}

/**
 * The core's notices (listen port taken, disk full) and folders that stopped
 * syncing, on their own high-importance "Problems" channel. They are rare and
 * mean syncing is blocked, so a new one alerts. Notices stay until cleared
 * here or in the app; a folder's notification goes once the folder recovers.
 * Both go when the core stops.
 */
object ProblemNotifications {
    private const val CHANNEL_ID = "problems"
    private const val NOTIFICATION_ID = 3
    private const val FOLDER_TAG = "folder"
    private const val NO_ACCESS_ID = 4
    private const val NO_LOCAL_NETWORK_ID = 5
    private const val MEMORY_LIMIT_ID = 6

    /** Extra on the launch intent that opens one folder's screen. */
    const val EXTRA_OPEN_FOLDER = "eu.stedb.mysyncthing.OPEN_FOLDER"

    /** Notices already alerted for, so an unchanged or shrinking list updates silently. */
    private var alerted = emptySet<SystemError>()

    fun createChannel(base: Context) {
        val context = base.inAppLanguage()
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_problems_description)
            }
        )
    }

    fun update(base: Context, errors: List<SystemError>) {
        val context = base.inAppLanguage()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (errors.isEmpty()) {
            nm.cancel(NOTIFICATION_ID)
            alerted = emptySet()
            return
        }
        val fresh = errors.any { it !in alerted }
        alerted = errors.toSet()
        val latest = errors.last()
        val title = context.resources.getQuantityString(R.plurals.problems_title, errors.size, errors.size)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, launch, PendingIntent.FLAG_IMMUTABLE)
        val clear = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID, Intent(context, ClearProblemsReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.widget_ic_problem)
            .setContentTitle(title)
            .setContentText(latest.text.resolve(context))
            .setStyle(NotificationCompat.BigTextStyle().bigText(errors.reversed().take(5).joinToString("\n") { it.text.resolve(context) }))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_clear), clear)
            .setOnlyAlertOnce(!fresh)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            // Messages can name paths and addresses, so they stay off the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.widget_ic_problem)
                    .setContentTitle(title)
                    .build()
            )
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    /**
     * Syncing stopped because All files access is off; tapping opens the app,
     * whose setup checklist asks for it again. Cleared once the service starts with it.
     */
    fun noFileAccess(base: Context, show: Boolean) =
        stopped(base, NO_ACCESS_ID, show, R.string.problem_no_file_access_title, R.string.problem_no_file_access)

    /**
     * Syncing stopped because Android 17 asks for the local network and it is not allowed: right after an
     * update to a version that targets Android 17 (Android restarts the app), or after the user took it
     * away. Tapping opens the setup checklist. Cleared once the service starts with it.
     */
    fun noLocalNetwork(base: Context, show: Boolean) =
        stopped(base, NO_LOCAL_NETWORK_ID, show, R.string.problem_no_file_access_title, R.string.problem_no_local_network)

    private fun stopped(base: Context, id: Int, show: Boolean, @StringRes title: Int, @StringRes text: Int) {
        val context = base.inAppLanguage()
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!show) {
            nm.cancel(id)
            return
        }
        notify(context, id, context.getString(title), context.getString(text))
    }

    /**
     * Android's memory limit ended the app (Android 17); the sticky service has started it again. Shown
     * once per end (MemoryLimit.kt), with the memory it used and when.
     */
    fun memoryLimit(base: Context, end: ProcessExit) {
        val context = base.inAppLanguage()
        val at = time(Instant.ofEpochMilli(end.timestamp).atZone(ZoneId.systemDefault()), "jm")
        // SI megabytes, as every size in the app; Android counts the resident set in KiB.
        val text = text(R.string.problem_memory_limit, num(end.rssKb * 1024 / 1_000_000), at).resolve(context)
        notify(context, MEMORY_LIMIT_ID, context.getString(R.string.problem_memory_limit_title), text)
    }

    /** A notice that opens the app; the app shows what to do. */
    private fun notify(context: Context, id: Int, title: String, text: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(NotificationManager::class.java).notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.widget_ic_problem)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build(),
        )
    }

    /**
     * One notification per folder in error, gone once the folder recovers. Only
     * a new one alerts; read from the shade, so this holds across process restarts.
     */
    fun updateFolders(base: Context, problems: List<FolderProblem>) {
        val context = base.inAppLanguage()
        val nm = context.getSystemService(NotificationManager::class.java)
        val shown = nm.activeNotifications.filter { it.tag == FOLDER_TAG }.map { it.id }.toSet()
        val wanted = problems.associateBy { it.id.hashCode() }
        (shown - wanted.keys).forEach { nm.cancel(FOLDER_TAG, it) }
        wanted.forEach { (id, p) -> nm.notify(FOLDER_TAG, id, folderNotification(context, p)) }
    }

    private fun folderNotification(context: Context, p: FolderProblem): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_FOLDER, p.id)
        val open = PendingIntent.getActivity(
            context, p.id.hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(R.string.problem_folder_stopped, p.name)
        val error = problemText(p.error).resolve(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.widget_ic_problem)
            .setContentTitle(title)
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setContentIntent(open)
            .setOnlyAlertOnce(true) // a changed message on a shown one updates silently
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            // Folder names and paths stay off the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.widget_ic_problem)
                    .setContentTitle(context.getString(R.string.problem_folder_stopped_public))
                    .build()
            )
            .build()
    }
}

/** Clears the core's notices, like Clear in the app. */
class ClearProblemsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val client = (SyncthingCore.state.value as? CoreState.Running)?.client ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { SyncStatus.clearErrors(client) }
            } finally {
                pending.finish()
            }
        }
    }
}
