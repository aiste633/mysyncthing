package eu.stedb.mysyncthing.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts Syncthing at boot and after an update of the app, which kills it.
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
                val s = Settings(context)
                val update = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
                if (startsSyncthing(update, s.runConditions.first().startOnBoot, s.stoppedByUser.first(), s.startedThisBoot.first())) {
                    SyncthingService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** A boot starts Syncthing with Start at boot on; an update also when it ran before, unless the user stopped it. */
internal fun startsSyncthing(update: Boolean, startOnBoot: Boolean, stoppedByUser: Boolean, startedThisBoot: Boolean): Boolean =
    !stoppedByUser && (startOnBoot || (update && startedThisBoot))
