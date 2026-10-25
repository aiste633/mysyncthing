package eu.stedb.mysyncthing.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * zxing's scanner, with its hint text kept clear of the navigation bar and the
 * cutout. The camera preview itself stays edge to edge.
 */
class ScanActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = findViewById<View>(com.google.zxing.client.android.R.id.zxing_status_view) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(status) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val gap = (16 * resources.displayMetrics.density).toInt()
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = bars.left + gap
                rightMargin = bars.right + gap
                bottomMargin = bars.bottom + gap
            }
            insets
        }
    }
}
