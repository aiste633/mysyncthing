package eu.stedb.mysyncthing.core

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

/**
 * The core's notices (listen port taken, folder marker missing, disk full) on
 * their own high-importance "Problems" channel. They are rare and mean syncing
 * is blocked, so a new one alerts; the notification stays until the notices are
 * cleared here or in the app, or the core stops.
 */
object ProblemNotifications {
    private const val CHANNEL_ID = "problems"
    private const val NOTIFICATION_ID = 3

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
            .setContentText(latest.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errors.reversed().take(5).joinToString("\n") { it.message }))
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
