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

/** One device asking to connect, or one folder offered by one device. */
data class Request(val key: String, val title: UiText, val text: UiText, val folder: String?, val device: String)

fun Snapshot.requests(): List<Request> {
    fun name(id: String) = devices.find { it.deviceID == id }?.name?.ifEmpty { null } ?: id.substringBefore('-')
    val forDevices = pendingDevices.map { (id, p) ->
        Request("device:$id", text(R.string.request_device_title, p.name.ifEmpty { id.substringBefore('-') }),
            text(R.string.request_device_text, id.substringBefore('-')), null, id)
    }
    val forFolders = pendingFolders.flatMap { (folder, p) ->
        p.offeredBy.map { (device, offer) ->
            val have = folders.any { it.id == folder }
            // The folder name leads: a heads-up shows one line of title and cut "<device> shares "…" before it.
            Request("folder:$folder:$device", text(R.string.request_folder_title, offer.label.ifEmpty { folder }),
                text(if (have) R.string.request_folder_text_have else R.string.request_folder_text, name(device)),
                folder, device)
        }
    }
    return forDevices + forFolders
}

/**
 * Separate "Requests" channel, so it can be muted without hiding the sync
 * status. Each request gets its own notification with Review and Ignore;
 * it disappears once the request is handled anywhere, even on another device.
 * No explicit group: cancelling a group summary also drops its children, and
 * Android groups several notifications from one app by itself.
 */
object RequestNotifications {
    /**
     * Android keeps a channel's importance once created, so raising it to high
     * (heads-up) needed a new ID; [OLD_CHANNEL_IDS] are removed at start.
     */
    private const val CHANNEL_ID = "requests-high"
    private val OLD_CHANNEL_IDS = listOf("requests")
    private const val TAG = "request"

    /** Extras on the launch intent that open the review screen for one request. */
    const val EXTRA_REVIEW_FOLDER = "eu.stedb.mysyncthing.REVIEW_FOLDER"
    const val EXTRA_REVIEW_DEVICE = "eu.stedb.mysyncthing.REVIEW_DEVICE"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        OLD_CHANNEL_IDS.forEach(nm::deleteNotificationChannel)
        // High: a request waits on the user, and missed in the shade it blocks syncing for good.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_requests_description)
            }
        )
    }

    /** Posts new requests (alerting once), and cancels those no longer pending; [repost] redraws shown ones too. */
    fun update(context: Context, requests: List<Request>, repost: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val shown = nm.activeNotifications.filter { it.tag == TAG }.map { it.id }.toSet()
        val wanted = requests.associateBy { it.key.hashCode() }
        (shown - wanted.keys).forEach { nm.cancel(TAG, it) }
        wanted.forEach { (id, r) -> if (repost || id !in shown) nm.notify(TAG, id, notification(context, r)) }
    }

    private fun notification(context: Context, r: Request) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.widget_ic_input)
        .setContentTitle(r.title.resolve(context))
        .setContentText(r.text.resolve(context))
        .setStyle(NotificationCompat.BigTextStyle().bigText(r.text.resolve(context)))
        .setContentIntent(review(context, r))
        .addAction(0, context.getString(R.string.action_review), review(context, r))
        .addAction(0, context.getString(R.string.action_ignore), ignore(context, r))
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        // Device names, IDs and addresses stay off the lock screen.
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.widget_ic_input)
                .setContentTitle(context.getString(if (r.folder != null) R.string.request_folder_public else R.string.request_device_public))
                .build()
        )
        .build()

    private fun review(context: Context, r: Request): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_REVIEW_DEVICE, r.device)
        r.folder?.let { launch.putExtra(EXTRA_REVIEW_FOLDER, it) }
        return PendingIntent.getActivity(context, r.key.hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ignore(context: Context, r: Request): PendingIntent {
        val intent = Intent(context, IgnoreRequestReceiver::class.java)
            .putExtra(EXTRA_REVIEW_DEVICE, r.device)
            .putExtra(EXTRA_REVIEW_FOLDER, r.folder)
        return PendingIntent.getBroadcast(context, r.key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Ignores a request for good, like the app's Ignore button. */
class IgnoreRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val client = (SyncthingCore.state.value as? CoreState.Running)?.client ?: return
        val device = intent.getStringExtra(RequestNotifications.EXTRA_REVIEW_DEVICE) ?: return
        val folder = intent.getStringExtra(RequestNotifications.EXTRA_REVIEW_FOLDER)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { if (folder != null) client.ignoreFolder(folder, device) else client.ignoreDevice(device) }
            } finally {
                pending.finish()
            }
        }
    }
}
