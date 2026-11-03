package eu.stedb.mysyncthing.core

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList

/**
 * [this] in the language the app's screens show: its own one from Android's per-app language page,
 * ahead of the phone's (as Android combines them for an activity), else the phone's.
 *
 * Needed for everything drawn outside the app: widget, notifications, channel names. Their context
 * follows the process's configuration, and Android keeps the app's language there only while the
 * process follows one of its activities: when the last one closes, it is reset to the phone's
 * language (WindowProcessController.unregisterActivityConfigurationListener). So after the user
 * left the app, the service drew the widget and its notification in the phone's language again.
 */
// AppBundleLocaleChanges: the app ships as whole APKs (F-Droid, GitHub), with every language in them.
@SuppressLint("AppBundleLocaleChanges")
fun Context.inAppLanguage(): Context {
    val manager = getSystemService(LocaleManager::class.java)
    val app = manager.applicationLocales
    val phone = manager.systemLocales
    val wanted = if (app.isEmpty) phone else LocaleList(*(app.toList() + phone.toList()).distinct().toTypedArray())
    return if (resources.configuration.locales == wanted) this
    else createConfigurationContext(Configuration().apply { setLocales(wanted) })
}

private fun LocaleList.toList() = List(size()) { get(it) }

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
        // Only the system's broadcast; an explicit intent with any other action is ignored.
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) return
        relocalize(context)
        SyncthingService.refreshNotification(context)
    }
}
