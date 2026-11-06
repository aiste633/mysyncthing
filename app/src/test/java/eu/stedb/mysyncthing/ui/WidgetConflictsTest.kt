package eu.stedb.mysyncthing.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.stedb.mysyncthing.core.StatusWidget
import eu.stedb.mysyncthing.core.Summary
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import eu.stedb.mysyncthing.core.R as CoreR

/** Conflict copies put an amber dot on the widget's status icon, read out with the status, and a tap opens Folders. */
@RunWith(AndroidJUnit4::class)
class WidgetConflictsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val widgets = shadowOf(AppWidgetManager.getInstance(app))
    private val id = widgets.createWidget(StatusWidget::class.java, CoreR.layout.widget_status)
    private fun view(): View = widgets.getViewFor(id)

    private fun render(conflicts: Int) = StatusWidget.render(app, Summary(SyncState.UpToDate, text(CoreR.string.status_up_to_date), conflicts = conflicts))

    @Test
    fun theDotShowsWhileCopiesExist() {
        render(2)
        assertEquals(View.VISIBLE, view().findViewById<View>(CoreR.id.widget_conflicts).visibility)
        val upToDate = app.getString(CoreR.string.status_up_to_date)
        assertEquals("$upToDate, ${app.resources.getQuantityString(CoreR.plurals.conflicts, 2, 2)}", view().findViewById<View>(CoreR.id.widget_title).contentDescription)

        render(0)
        assertEquals(View.GONE, view().findViewById<View>(CoreR.id.widget_conflicts).visibility)
        assertNull(view().findViewById<View>(CoreR.id.widget_title).contentDescription)
    }

    @Test
    fun aTapOpensFoldersWhileCopiesExist() {
        render(2)
        view().findViewById<View>(android.R.id.background).performClick()
        assertEquals("folders", shadowOf(app).nextStartedActivity.getStringExtra(StatusWidget.EXTRA_TAB))

        render(0)
        view().findViewById<View>(android.R.id.background).performClick()
        assertNull(shadowOf(app).nextStartedActivity.getStringExtra(StatusWidget.EXTRA_TAB))
    }
}
