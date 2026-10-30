package eu.stedb.mysyncthing.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * 4x1 home-screen widget: app mark (when wide enough), status icon and colour,
 * connected devices, progress and a pause button. It never polls;
 * [SyncthingService] pushes [render] when the summary changes, so an idle
 * phone costs nothing.
 */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, SyncStatus.summary.value)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_HAT_CHANGE) render(context, SyncStatus.summary.value)
        if (intent.action != ACTION_TOGGLE_PAUSE) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Settings(context).update { it.copy(pausedByUser = !it.pausedByUser) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_TOGGLE_PAUSE = "eu.stedb.mysyncthing.TOGGLE_PAUSE"
        private const val ACTION_HAT_CHANGE = "eu.stedb.mysyncthing.HAT_CHANGE"

        /** Extra on the launch intent: which tab to open ("devices"), so a device request opens where it is handled. */
        const val EXTRA_TAB = "eu.stedb.mysyncthing.TAB"

        fun render(context: Context, summary: Summary) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
            if (ids.isEmpty()) return
            val today = LocalDate.now()
            // The launcher picks the widest that fits: the app mark only where the text keeps its room.
            val hat = santaHat(today)
            manager.updateAppWidget(ids, RemoteViews(mapOf(NARROW to views(context, summary, mark = false, hat), WITH_MARK to views(context, summary, mark = true, hat))))
            scheduleHatChange(context, today)
        }

        /** The day the alarm below is set for in this process, so a render sets it once, not every time. */
        private var hatAlarm: LocalDate? = null

        /**
         * An idle phone may not render for days, so the widget is drawn again on the day the hat comes or
         * goes. An inexact alarm that doesn't wake the phone: twice a year, a few hours late at worst.
         */
        private fun scheduleHatChange(context: Context, today: LocalDate) {
            val next = santaHatChange(today)
            if (next == hatAlarm) return
            val redraw = PendingIntent.getBroadcast(
                context, 3, Intent(context, StatusWidget::class.java).setAction(ACTION_HAT_CHANGE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = next.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, redraw)
            hatAlarm = next
        }

        /** Below this width the mark would leave the status about four characters; the narrowest widget is 180 dp. */
        private val NARROW = SizeF(180f, 40f)
        private val WITH_MARK = SizeF(220f, 40f)

        private fun views(context: Context, s: Summary, mark: Boolean, hat: Boolean): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_status)
            val markVisibility = if (mark) View.VISIBLE else View.GONE
            v.setViewVisibility(R.id.widget_app_mark, markVisibility)
            v.setViewVisibility(R.id.widget_app_rule, markVisibility)
            // Both ways: the launcher applies an update onto the views it already has.
            v.setViewVisibility(R.id.widget_app_hat, if (hat) View.VISIBLE else View.GONE)
            val (icon, light, dark) = when (s.state) {
                SyncState.UpToDate -> Triple(R.drawable.widget_ic_ok, 0xFF1E7B34.toInt(), 0xFF81C995.toInt())
                SyncState.Syncing -> Triple(R.drawable.widget_ic_syncing, AMBER_LIGHT, AMBER_DARK)
                SyncState.Paused -> Triple(R.drawable.widget_ic_paused, AMBER_LIGHT, AMBER_DARK)
                SyncState.NeedsInput -> Triple(R.drawable.widget_ic_input, AMBER_LIGHT, AMBER_DARK)
                SyncState.Problem -> Triple(R.drawable.widget_ic_problem, 0xFFB3261E.toInt(), 0xFFFF6E66.toInt())
                SyncState.Stopped -> Triple(R.drawable.widget_ic_stopped, 0xFF5F6368.toInt(), 0xFFBDC1C6.toInt())
            }
            v.setImageViewResource(R.id.widget_status_icon, icon)
            v.setColorInt(R.id.widget_status_icon, "setColorFilter", light, dark)
            v.setTextViewText(R.id.widget_title, s.text.resolve(context))
            v.setTextViewText(R.id.widget_detail, detail(s).resolve(context))

            val progress = s.progress
            v.setViewVisibility(R.id.widget_progress, if (progress != null) View.VISIBLE else View.GONE)
            if (progress != null) {
                v.setProgressBar(R.id.widget_progress, 100, progress, false)
                v.setColorStateList(R.id.widget_progress, "setProgressTintList", ColorStateList.valueOf(light), ColorStateList.valueOf(dark))
            }

            v.setOnClickPendingIntent(android.R.id.background, openApp(context))
            val (buttonIcon, label, action) = when {
                s.state == SyncState.Stopped -> Triple(R.drawable.widget_ic_play, R.string.widget_start, startService(context))
                s.text == PAUSED_BY_USER -> Triple(R.drawable.widget_ic_play, R.string.widget_resume, togglePause(context))
                else -> Triple(R.drawable.widget_ic_pause, R.string.widget_pause, togglePause(context))
            }
            v.setImageViewResource(R.id.widget_button, buttonIcon)
            v.setContentDescription(R.id.widget_button, context.getString(label))
            v.setOnClickPendingIntent(R.id.widget_button, action)
            return v
        }

        /** Second line: the one connected device by name, else how many. */
        private fun detail(s: Summary): UiText = when {
            s.state == SyncState.Stopped -> text(R.string.widget_not_running)
            s.connected.size == 1 -> raw(s.connected.single())
            s.connected.size > 1 -> plural(R.plurals.widget_devices_connected, s.connected.size)
            else -> text(R.string.widget_no_device)
        }

        private fun openApp(context: Context): PendingIntent {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val snap = SyncStatus.snapshot.value
            if (snap.pendingDevices.isNotEmpty() && snap.pendingFolders.isEmpty()) launch.putExtra(EXTRA_TAB, "devices")
            return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun togglePause(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1, Intent(context, StatusWidget::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // A widget tap counts as user interaction, so the foreground service may start from it.
        private fun startService(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context, 2, Intent(context, SyncthingService::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private val AMBER_LIGHT = 0xFF8A5A00.toInt()
        private val AMBER_DARK = 0xFFFDD663.toInt()
    }
}
