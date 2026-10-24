package eu.stedb.mysyncthing.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
                updateNotification("Failed to start")
                return@launch
            }
            launch { watchCore() }
            combine(settings.runConditions, deviceState(this@SyncthingService)) { c, d -> c.blockedBy(d) }
                .collect { reason -> applyConditions(running.client, reason) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        // Let the core flush its database before the process may be killed.
        runBlocking { SyncthingCore.stop() }
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
        updateNotification(reason?.let { "Paused: $it" } ?: "Syncing")
    }

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

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "eu.stedb.mysyncthing.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncthingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncthingService::class.java))
        }
    }
}
