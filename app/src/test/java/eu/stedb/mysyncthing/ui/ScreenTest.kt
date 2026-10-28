package eu.stedb.mysyncthing.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Base of the screen tests: the app's real composables on Robolectric (Android 16,
 * see robolectric.properties), fed with [Fixtures] instead of a running core. The
 * view model is real too; with no core it reaches no network.
 */
@RunWith(AndroidJUnit4::class)
abstract class ScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    protected val app: Application get() = ApplicationProvider.getApplicationContext()
    protected val vm by lazy { MainViewModel(app) }

    protected fun str(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    protected fun show(content: @Composable () -> Unit) = compose.setContent { AppTheme(content) }
}
