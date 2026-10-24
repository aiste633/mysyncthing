package eu.stedb.mysyncthing.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** `specialUse` services may start from BOOT_COMPLETED on Android 15+, unlike `dataSync`. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val enabled = runBlocking { Settings(context).runConditions.first().startOnBoot }
        if (enabled) SyncthingService.start(context)
    }
}
