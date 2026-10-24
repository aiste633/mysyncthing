package eu.stedb.mysyncthing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import eu.stedb.mysyncthing.core.StatusWidget
import eu.stedb.mysyncthing.ui.App

class MainActivity : ComponentActivity() {
    /** Tab requested by the widget; cleared once the app has navigated there. */
    private val requestedTab = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestedTab.value = intent.getStringExtra(StatusWidget.EXTRA_TAB)
        setContent { App(requestedTab = requestedTab.value, onTabShown = { requestedTab.value = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.value = intent.getStringExtra(StatusWidget.EXTRA_TAB)
    }
}
