package eu.stedb.mysyncthing.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    /** Extra on the launch intent that opens one folder's screen. */
    const val EXTRA_OPEN_FOLDER = "eu.stedb.mysyncthing.OPEN_FOLDER"

    /** Notices already alerted for, so an unchanged or shrinking list updates silently. */
    private var alerted = emptySet<SystemError>()

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Problems", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Errors that stop Syncthing from syncing"
            }
        )
    }

    fun update(context: Context, errors: List<SystemError>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (errors.isEmpty()) {
            nm.cancel(NOTIFICATION_ID)
            alerted = emptySet()
            return
        }
        val fresh = errors.any { it !in alerted }
        alerted = errors.toSet()
        val latest = errors.last()
        val title = if (errors.size == 1) "Syncthing has a problem" else "Syncthing has ${errors.size} problems"
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, launch, PendingIntent.FLAG_IMMUTABLE)
        val clear = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID, Intent(context, ClearProblemsReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.widget_ic_problem)
            .setContentTitle(title)
            .setContentText(latest.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errors.reversed().take(5).joinToString("\n") { it.text }))
            .setContentIntent(open)
            .addAction(0, "Clear", clear)
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
    fun noFileAccess(context: Context, show: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!show) {
            nm.cancel(NO_ACCESS_ID)
            return
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, NO_ACCESS_ID, launch, PendingIntent.FLAG_IMMUTABLE)
        val text = "MySyncthing has no access to your files. Allow All files access to sync again."
        nm.notify(
            NO_ACCESS_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.widget_ic_problem)
                .setContentTitle("Syncing stopped")
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
    fun updateFolders(context: Context, problems: List<FolderProblem>) {
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
        val title = "${p.name} stopped syncing"
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.widget_ic_problem)
            .setContentTitle(title)
            .setContentText(p.error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(p.error))
            .setContentIntent(open)
            .setOnlyAlertOnce(true) // a changed message on a shown one updates silently
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            // Folder names and paths stay off the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.widget_ic_problem)
                    .setContentTitle("A folder stopped syncing")
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
