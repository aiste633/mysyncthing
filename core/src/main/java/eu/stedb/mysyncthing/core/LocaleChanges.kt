package eu.stedb.mysyncthing.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The phone's or this app's language changed: what the app shows outside
 * itself follows. Channel names in system settings are only updated by
 * creating the channels again; the widget and posted notifications keep the
 * text they were drawn with until drawn again, which re-posting does silently.
 */
fun relocalize(context: Context) {
    SyncthingService.createChannel(context)
    RequestNotifications.createChannel(context)
    ProblemNotifications.createChannel(context)
    StatusWidget.render(context, SyncStatus.summary.value)
    val snap = SyncStatus.snapshot.value
    if (!snap.loaded) return
    RequestNotifications.update(context, snap.requests(), repost = true)
    ProblemNotifications.update(context, snap.systemErrors)
    ProblemNotifications.updateFolders(context, snap.folderProblems())
}

/**
 * LOCALE_CHANGED comes for the phone's language and, aimed at this app alone,
 * for the app's own one set on Android's per-app language page; it starts the
 * process if needed, so a stopped service is covered too. (APPLICATION_LOCALE_CHANGED
 * goes only to holders of a permission apps can't get.) The status notification
 * is redrawn by the service, if it runs.
 */
class LocaleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        relocalize(context)
        SyncthingService.refreshNotification(context)
    }
}
