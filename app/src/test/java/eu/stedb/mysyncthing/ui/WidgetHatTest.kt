package eu.stedb.mysyncthing.ui

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import eu.stedb.mysyncthing.core.R as CoreR

/**
 * The Santa hat is drawn in the app mark's own box: a drawable of the mark's size, filling the mark's frame,
 * with no offset or scale. A drawable cannot draw outside its bounds, so the hat cannot move, resize or
 * reach anything around the mark.
 */
@RunWith(AndroidJUnit4::class)
class WidgetHatTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun theHatHasTheMarksSize() {
        val mark = app.getDrawable(CoreR.drawable.widget_ic_app)!!
        val hat = app.getDrawable(CoreR.drawable.widget_ic_hat)!!
        assertEquals(mark.intrinsicWidth, hat.intrinsicWidth)
        assertEquals(mark.intrinsicHeight, hat.intrinsicHeight)
    }

    @Test
    fun theWidgetDrawsTheHatInTheMarksFrame() {
        val widget = LayoutInflater.from(app).inflate(CoreR.layout.widget_status, null)
        val frame = widget.findViewById<ViewGroup>(CoreR.id.widget_app_mark)
        val hat = widget.findViewById<View>(CoreR.id.widget_app_hat)
        assertSame(frame, hat.parent)
        assertTrue(frame.clipChildren)
        val params = hat.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height)
        assertEquals(listOf(0, 0, 0, 0), listOf(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin))
        assertEquals(listOf(0f, 0f, 1f, 1f), listOf(hat.translationX, hat.translationY, hat.scaleX, hat.scaleY))
    }
}
