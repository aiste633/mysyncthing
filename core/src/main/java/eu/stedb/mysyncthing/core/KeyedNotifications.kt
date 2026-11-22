package eu.stedb.mysyncthing.core

import android.app.Notification
import android.app.NotificationManager

/**
 * Notifications that come one per key: a request, a folder that stopped. The key itself tells them
 * apart, as the notification's tag ([kind], a colon, the key). Up to 0.5.0 the ID was the key's
 * `hashCode()`: two keys with one hash code shared a notification and its buttons, and folder IDs
 * come from other devices.
 *
 * The ID is a slot: a number no other notification of the app has. It is also the request code of the
 * PendingIntents that open the app, as Android tells those apart by request code and not by extras:
 * every shown notification has its own. The key cannot go into that intent itself (as its data or
 * identifier): the task a tap opens would then no longer be the launcher's, and every later start
 * from the launcher would add a copy of the main screen (see MainActivity). A broadcast opens no
 * task, so there the key is the intent's identifier.
 *
 * What is shown is read from the shade, so all of this holds across process restarts.
 */
internal class KeyedNotifications(kind: String, private val oldTag: String) {
    private val prefix = "$kind:"

    /**
     * Shows one notification per key of [wanted] and removes the kind's others. A key not shown yet
     * is posted, one already shown only with [repost]; [build] gets the notification's slot.
     */
    fun <T> update(nm: NotificationManager, wanted: Map<String, T>, repost: Boolean, build: (T, slot: Int) -> Notification) {
        val active = nm.activeNotifications
        val shown = active.filter { it.tag?.startsWith(prefix) == true }.associate { it.tag.removePrefix(prefix) to it.id }
        (shown - wanted.keys).forEach { (key, slot) -> nm.cancel(prefix + key, slot) }
        // The slots of those just removed count as taken too: a tap that was on its way to one of them
        // must not reach a request posted in the same breath.
        val taken = active.mapTo(mutableSetOf()) { it.id }
        wanted.forEach { (key, item) ->
            val slot = shown[key]
            if (slot == null || repost) {
                val s = slot ?: generateSequence(FIRST_SLOT) { it + 1 }.first(taken::add)
                nm.notify(prefix + key, s, build(item, s))
            }
        }
    }

    /** Removes what 0.5.0 and older left in the shade: one tag for the whole kind, the key's hash code as ID. */
    fun removeOld(nm: NotificationManager) =
        nm.activeNotifications.filter { it.tag == oldTag }.forEach { nm.cancel(oldTag, it.id) }

    private companion object {
        /** Above the fixed IDs and request codes of the app's other notifications and of the widget (0 to 6). */
        const val FIRST_SLOT = 1000
    }
}
