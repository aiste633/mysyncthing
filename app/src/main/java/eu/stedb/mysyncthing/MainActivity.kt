package eu.stedb.mysyncthing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import eu.stedb.mysyncthing.core.RequestNotifications
import eu.stedb.mysyncthing.core.StatusWidget
import eu.stedb.mysyncthing.ui.App
import eu.stedb.mysyncthing.ui.LaunchTarget

class MainActivity : ComponentActivity() {
    /** Where the widget or a request notification asked to go; cleared once shown. */
    private val target = mutableStateOf<LaunchTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) target.value = intent.launchTarget()
        setContent { App(target = target.value, onTargetShown = { target.value = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        target.value = intent.launchTarget()
    }

    private fun Intent.launchTarget(): LaunchTarget? {
        val folder = getStringExtra(RequestNotifications.EXTRA_REVIEW_FOLDER)
        val device = getStringExtra(RequestNotifications.EXTRA_REVIEW_DEVICE)
        val tab = getStringExtra(StatusWidget.EXTRA_TAB)
        return when {
            folder != null && device != null -> LaunchTarget.ReviewFolder(folder, device)
            device != null -> LaunchTarget.ReviewDevice(device)
            tab != null -> LaunchTarget.Tab(tab)
            else -> null
        }
    }
}
