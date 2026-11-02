package eu.stedb.mysyncthing.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.stedb.mysyncthing.core.StatusWidget
import eu.stedb.mysyncthing.core.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import eu.stedb.mysyncthing.core.R as CoreR

/**
 * The widget's second line follows the phone's font size, also when the status does not change:
 * on the PC emulator it stayed, cut, after switching to the largest size until the next status.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRedrawTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun detailVisibility(id: Int): Int =
        shadowOf(AppWidgetManager.getInstance(app)).getViewFor(id).findViewById<View>(CoreR.id.widget_detail).visibility

    @Test
    fun theSecondLineFollowsTheFontSize() {
        val id = shadowOf(AppWidgetManager.getInstance(app)).createWidget(StatusWidget::class.java, CoreR.layout.widget_status)
        RuntimeEnvironment.setFontScale(1f)
        StatusWidget.render(app, SyncStatus.summary.value)
        assertEquals(View.VISIBLE, detailVisibility(id))

        // The largest size: no new status, only the configuration change.
        RuntimeEnvironment.setFontScale(2f)
        StatusWidget.redrawIfStale(app)
        assertEquals(View.GONE, detailVisibility(id))

        // Back again.
        RuntimeEnvironment.setFontScale(1f)
        StatusWidget.redrawIfStale(app)
        assertEquals(View.VISIBLE, detailVisibility(id))
    }
}
