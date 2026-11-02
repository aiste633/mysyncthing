package eu.stedb.mysyncthing.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.stedb.mysyncthing.core.detailFits
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * The widget's second line ("Last device seen 14:41") was cut at the bottom in German at the
 * largest font (PC emulator). It shows only while both lines fit the bar's 52 dp.
 */
@RunWith(AndroidJUnit4::class)
class WidgetFontTest {
    private fun fitsAt(scale: Float): Boolean {
        RuntimeEnvironment.setFontScale(scale)
        return detailFits(ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics)
    }

    @Test
    fun secondLineOnlyWhereItFits() {
        val scales = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)
        val fits = scales.map(::fitsAt)
        assertEquals("up to 130 %", listOf(true, true, true, true), fits.take(4))
        assertEquals("not at 200 %", false, fits.last())
        // Once it no longer fits, a larger font never brings it back.
        assertEquals(fits.sortedDescending(), fits)
    }
}
