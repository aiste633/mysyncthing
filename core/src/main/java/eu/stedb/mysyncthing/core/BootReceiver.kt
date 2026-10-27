package eu.stedb.mysyncthing.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * `specialUse` services may start from BOOT_COMPLETED on Android 15+, unlike `dataSync`.
 * Android 15+ also sends BOOT_COMPLETED when the app leaves the stopped state
 * (the first start after a force-stop or an install), so it is no proof of a
 * boot: a user's Stop ends with the boot it was made in (Settings.stoppedByUser),
 * not with this broadcast.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // The settings are read off the main thread: a slow read there (right after an install, as the
        // broadcast above comes then) blocked onReceive into an ANR. goAsync keeps the broadcast, and its
        // right to start the service, open.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = Settings(context)
                if (settings.runConditions.first().startOnBoot && !settings.stoppedByUser.first()) SyncthingService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
