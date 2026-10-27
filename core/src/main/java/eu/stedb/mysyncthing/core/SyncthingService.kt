package eu.stedb.mysyncthing.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Keeps the Go core alive as a `specialUse` foreground service (no daily cap,
 * may start at boot on Android 15+). Run conditions pause/resume devices via
 * REST instead of stopping the core, so restarts stay rare.
 */
class SyncthingService : LifecycleService() {
    private lateinit var settings: Settings

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        createChannel()
        RequestNotifications.createChannel(this)
        ProblemNotifications.createChannel(this)
        // An unloaded snapshot (no core) passes as null, so what a new run loads is always posted,
        // also when it equals what was shown before failed() cleared it.
        lifecycleScope.launch {
            SyncStatus.snapshot.map { s -> s.takeIf { it.loaded }?.requests() }.distinctUntilChanged().filterNotNull()
                .collect { RequestNotifications.update(this@SyncthingService, it) }
        }
        lifecycleScope.launch {
            SyncStatus.snapshot.map { s -> s.takeIf { it.loaded }?.systemErrors }.distinctUntilChanged().filterNotNull()
                .collect { ProblemNotifications.update(this@SyncthingService, it) }
        }
        lifecycleScope.launch {
            SyncStatus.snapshot.map { s -> s.takeIf { it.loaded }?.folderProblems() }.distinctUntilChanged().filterNotNull()
                .collect { ProblemNotifications.updateFolders(this@SyncthingService, it) }
        }
        // Renders at most every 2 s; a StateFlow collector always ends on the latest summary.
        lifecycleScope.launch {
            SyncStatus.summary.collect {
                StatusWidget.render(this@SyncthingService, it)
                delay(2_000)
            }
        }
        startForeground(NOTIFICATION_ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    /** The core's run: started by [onStartCommand], over once the core failed (a later start tries again). */
    private var run: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                // Recorded first, so opening the app (or turning the phone) doesn't start it again.
                SyncthingCore.setStoppedByUser(true)
                lifecycleScope.launch {
                    settings.setStoppedByUser(true)
                    stopSelf()
                }
            }
            ACTION_REFRESH -> lastShown?.let { (text, public) -> lastShown = null; updateNotification(text, public) }
            // A start (the user, the app opening, boot, a sticky restart) runs the core unless it already runs.
            else -> {
                lifecycleScope.launch { settings.setStoppedByUser(false) }
                if (run?.isActive != true) run = lifecycleScope.launch { runCore() }
            }
        }
        return START_STICKY
    }

    private suspend fun runCore() {
        // Without All files access the app still sees the files it wrote itself, .stfolder included, so the
        // core's folder-marker check passes and every other file would be scanned as deleted and synced as such.
        // Android kills the app when the access is taken away; this keeps the sticky restart (and boot) from
        // starting the core again.
        if (!Environment.isExternalStorageManager()) {
            ProblemNotifications.noFileAccess(this, true)
            stopSelf()
            return
        }
        ProblemNotifications.noFileAccess(this, false)
        updateNotification("Starting")

        val home = File(filesDir, "syncthing")
        // Locked: a start during an import waits for it, then sees its setup and settings.
        SyncthingCore.homeLock.use {
            // A phone migration copies the identity and config but not the database (data_extraction_rules.xml).
            // A Fork import leaves no database either, but explains that itself (ForkImport).
            val fresh = settings.takeFreshDatabaseExpected()
            if (File(home, "config.xml").exists() && !File(home, "index-v2").exists() && !fresh) settings.setMovedFromOtherPhone(true)
            SyncthingCore.start(home, settings::credentials)
        }
        val running = SyncthingCore.state.first { it !is CoreState.Starting }
        if (running !is CoreState.Running) return failed(running, "Failed to start")
        coroutineScope {
            val work = launch {
                launch { SyncStatus.follow(running.client) }
                launch { applyPhoneDefaults(running.client) }

                val device = deviceState(this@SyncthingService).stateIn(this)
                val blockedBy = combine(settings.runConditions, device) { c, d -> c.blockedBy(d) }.distinctUntilChanged()
                launch { blockedBy.collect { reason -> applyConditions(running.client, reason) } }
                combine(SyncthingCore.state, SyncStatus.snapshot, blockedBy, device) { core, snap, reason, d ->
                    summarize(core, snap, reason, d.online)
                }.collect { summary ->
                    SyncStatus.publish(summary)
                    updateNotification(summary.line(), summary.publicText)
                }
            }
            watchCore()
            work.cancel()
        }
        failed(SyncthingCore.state.value, "Stopped with an error")
    }

    /**
     * The core did not start or stopped with an error. The service stays, so
     * the app can show why and start it again; the notices of a core that no
     * longer runs go (also those a crashed process left in the shade).
     */
    private fun failed(state: CoreState, text: String) {
        SyncStatus.publish(summarize(state, Snapshot(), null, true))
        updateNotification(text)
        clearCoreNotices()
    }

    /** Requests and problems shown for a core that no longer runs. */
    private fun clearCoreNotices() {
        RequestNotifications.update(this, emptyList()) // Ignore needs a running core
        ProblemNotifications.update(this, emptyList())
        ProblemNotifications.updateFolders(this, emptyList())
    }

    override fun onDestroy() {
        // Let the core flush its database before the process may be killed.
        runBlocking { SyncthingCore.stop() }
        Summary(SyncState.Stopped, if (Environment.isExternalStorageManager()) "Stopped" else "Stopped: no access to files").let {
            SyncStatus.publish(it)
            StatusWidget.render(this, it) // the collector above is already cancelled
        }
        clearCoreNotices()
        super.onDestroy()
    }

    private suspend fun applyConditions(client: RestClient, reason: String?) {
        runCatching {
            val pausedByUs = settings.conditionPause()
            if (reason != null && pausedByUs == null) {
                val userPaused = client.devices().filter { it.paused }.map { it.deviceID }.toSet()
                // Unrecorded, the pause would never be undone; the next change of conditions tries again.
                if (!settings.setConditionPause(userPaused)) return@runCatching
                client.setAllPaused(true)
            } else if (reason == null && pausedByUs != null) {
                client.devices().filter { it.deviceID !in pausedByUs }.forEach { client.setDevicePaused(it.deviceID, false) }
                settings.setConditionPause(null)
            }
        }
    }

    /**
     * Upstream names the device after the hostname, which is "localhost" on
     * Android. Only replaces that default, never a name the user chose.
     * Usage reporting is left to upstream's own consent prompt.
     */
    private suspend fun applyPhoneDefaults(client: RestClient) {
        runCatching {
            val me = client.device(client.status().myID)
            if (me.name.isBlank() || me.name == "localhost") client.setDeviceName(me.deviceID, phoneName())
        }
    }

    /** The name the user gave the phone in system settings, else its model. */
    private fun phoneName(): String =
        android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Returns once the running core has stopped on its own. */
    private suspend fun watchCore() {
        while (SyncthingCore.state.value is CoreState.Running) {
            delay(10_000)
            SyncthingCore.checkAlive()
        }
    }

    /**
     * Android raises a foreground service's MIN channel to LOW, so the
     * notification shows in the shade on every phone; it stays out of the
     * status bar. The user can hide it by turning the channel off.
     */
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sync service", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
    }

    /** [publicText] is for the lock screen: the state without device names. */
    private fun notification(text: String, publicText: String = text): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncthingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Syncthing")
            .setContentText(text)
            .setContentIntent(content)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Its own group keeps it out of Android's automatic group of our other
            // notifications, whose summary would otherwise inherit "ongoing" and
            // could not be swiped away while a request or problem shows.
            .setGroup(SERVICE_GROUP)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("Syncthing")
                    .setContentText(publicText)
                    .setGroup(SERVICE_GROUP)
                    .build()
            )
            .build()
    }

    /** The texts last posted, so an unchanged summary posts nothing. */
    private var lastShown: Pair<String, String>? = null

    private fun updateNotification(text: String, publicText: String = text) {
        if (text to publicText == lastShown) return
        lastShown = text to publicText
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, publicText))
    }

    companion object {
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1
        private const val SERVICE_GROUP = "service"
        private const val ACTION_STOP = "eu.stedb.mysyncthing.STOP"
        private const val ACTION_REFRESH = "eu.stedb.mysyncthing.REFRESH_NOTIFICATION"

        /**
         * Starts the service, and the core unless it runs; also after it failed.
         * For a start the user asked for (or boot, an import): it ends a stop by the user.
         */
        fun start(context: Context) {
            SyncthingCore.setStoppedByUser(false)
            context.startForegroundService(Intent(context, SyncthingService::class.java))
        }

        /** The app came to the front: starts Syncthing unless the user stopped it. */
        suspend fun startUnlessStopped(context: Context) {
            if (SyncthingCore.stoppedByUser.value) return
            if (Settings(context).stoppedByUser.first()) return SyncthingCore.setStoppedByUser(true)
            start(context)
        }

        /** The user's stop: it lasts until the user starts Syncthing again or the phone restarts. */
        fun stopByUser(context: Context) {
            SyncthingCore.setStoppedByUser(true)
            context.startService(Intent(context, SyncthingService::class.java).setAction(ACTION_STOP))
        }

        /** Not the user's stop (an import replacing the setup): the app may start it again. */
        fun stop(context: Context) {
            context.stopService(Intent(context, SyncthingService::class.java))
        }

        /** Re-posts the notification, e.g. after the user allowed notifications while the service ran. */
        fun refreshNotification(context: Context) {
            if (SyncthingCore.state.value == CoreState.Stopped) return
            context.startService(Intent(context, SyncthingService::class.java).setAction(ACTION_REFRESH))
        }
    }
}
