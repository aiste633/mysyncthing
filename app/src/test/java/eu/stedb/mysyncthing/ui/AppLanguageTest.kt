package eu.stedb.mysyncthing.ui

import android.app.Application
import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.os.LocaleList
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.stedb.mysyncthing.core.ProblemNotifications
import eu.stedb.mysyncthing.core.StatusWidget
import eu.stedb.mysyncthing.core.Summary
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.SyncthingService
import eu.stedb.mysyncthing.core.text
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import eu.stedb.mysyncthing.core.R as CoreR

/**
 * What the app draws outside itself is in the app's own language, also when the process's
 * configuration has the phone's: Android puts it back to the phone's once the app's last screen
 * closes. On the PC emulator the widget and the status notification fell back to English after
 * the app's language was set to German. Robolectric leaves the configuration alone when the app's
 * language is set, which is the state after the last screen closed.
 */
@RunWith(AndroidJUnit4::class)
class AppLanguageTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val locales = app.getSystemService(LocaleManager::class.java)

    private fun german(id: Int): String =
        app.createConfigurationContext(Configuration().apply { setLocales(LocaleList.forLanguageTags("de")) }).getString(id)

    /** [shown] is the German for [id], which differs from the English (else the test would show nothing). */
    private fun assertGerman(id: Int, shown: String) {
        assertNotEquals(app.getString(id), german(id))
        assertEquals(german(id), shown)
    }

    @After
    fun phoneLanguage() {
        locales.applicationLocales = LocaleList.getEmptyLocaleList()
    }

    private fun widgetTitle(): String {
        val widgets = shadowOf(AppWidgetManager.getInstance(app))
        val id = widgets.createWidget(StatusWidget::class.java, CoreR.layout.widget_status)
        StatusWidget.render(app, Summary(SyncState.Stopped, text(CoreR.string.status_stopped)))
        return widgets.getViewFor(id).findViewById<TextView>(CoreR.id.widget_title).text.toString()
    }

    private fun channelName(): String {
        SyncthingService.createChannel(app)
        return app.getSystemService(NotificationManager::class.java).getNotificationChannel("sync").name.toString()
    }

    private fun problemTitle(): String {
        ProblemNotifications.createChannel(app)
        ProblemNotifications.noFileAccess(app, true)
        return shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
            .extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    }

    private fun serviceText(): String {
        val service = Robolectric.buildService(SyncthingService::class.java).create().get()
        return shadowOf(service).lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    }

    @Test
    fun theWidgetFollowsTheAppLanguage() {
        locales.applicationLocales = LocaleList.forLanguageTags("de")
        assertGerman(CoreR.string.status_stopped, widgetTitle())
    }

    @Test
    fun channelNamesFollowTheAppLanguage() {
        locales.applicationLocales = LocaleList.forLanguageTags("de")
        assertGerman(CoreR.string.channel_service, channelName())
    }

    @Test
    fun notificationsFollowTheAppLanguage() {
        locales.applicationLocales = LocaleList.forLanguageTags("de")
        assertGerman(CoreR.string.problem_no_file_access_title, problemTitle())
    }

    @Test
    fun theServiceNotificationFollowsTheAppLanguage() {
        locales.applicationLocales = LocaleList.forLanguageTags("de")
        assertGerman(CoreR.string.status_starting, serviceText())
    }

    @Test
    fun withoutAnAppLanguageThePhoneLanguage() {
        assertEquals(app.getString(CoreR.string.status_stopped), widgetTitle())
        assertEquals(app.getString(CoreR.string.channel_service), channelName())
    }
}
