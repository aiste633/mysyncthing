package eu.stedb.mysyncthing.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
        startForeground(NOTIFICATION_ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        lifecycleScope.launch {
            SyncthingCore.start(File(filesDir, "syncthing"), settings.credentials())
            val running = SyncthingCore.state.first { it !is CoreState.Starting }
            if (running !is CoreState.Running) {
                SyncStatus.publish(summarize(running, Snapshot(), null, true))
                updateNotification("Failed to start")
                return@launch
            }
            launch { watchCore() }
            launch { SyncStatus.follow(running.client) }
            launch { applyPhoneDefaults(running.client) }

            val device = deviceState(this@SyncthingService).stateIn(this)
            val blockedBy = combine(settings.runConditions, device) { c, d -> c.blockedBy(d) }.distinctUntilChanged()
            launch { blockedBy.collect { reason -> applyConditions(running.client, reason) } }
            combine(SyncthingCore.state, SyncStatus.snapshot, blockedBy, device) { core, snap, reason, d ->
                summarize(core, snap, reason, d.online)
            }.collect { summary ->
                SyncStatus.publish(summary)
                updateNotification(summary.line())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_REFRESH -> lastText?.let { lastText = null; updateNotification(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Let the core flush its database before the process may be killed.
        runBlocking { SyncthingCore.stop() }
        SyncStatus.publish(Summary(SyncState.Stopped, "Stopped"))
        super.onDestroy()
    }

    private suspend fun applyConditions(client: RestClient, reason: String?) {
        runCatching {
            val pausedByUs = settings.conditionPause()
            if (reason != null && pausedByUs == null) {
                val userPaused = client.devices().filter { it.paused }.map { it.deviceID }.toSet()
                settings.setConditionPause(userPaused)
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

    private suspend fun watchCore() {
        SyncthingCore.state.filterIsInstance<CoreState.Running>().first()
        while (true) {
            delay(10_000)
            SyncthingCore.checkAlive()
            if (SyncthingCore.state.value is CoreState.Failed) {
                updateNotification("Stopped with an error")
                return
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sync service", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
    }

    private fun notification(text: String): Notification {
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
            .build()
    }

    private var lastText: String? = null

    private fun updateNotification(text: String) {
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "eu.stedb.mysyncthing.STOP"
        private const val ACTION_REFRESH = "eu.stedb.mysyncthing.REFRESH_NOTIFICATION"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncthingService::class.java))
        }

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
