package eu.stedb.mysyncthing.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters

/**
 * What TalkBack reads: on every screen, each control a person can tap or switch has a
 * name. A Switch or Checkbox inside a row instead of the row itself being toggleable,
 * or an icon button without a description, is read as just "Button" or "Switch".
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class AccessibilityTest(private val screen: ScreenCase) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyControlIsNamed() {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext())
        compose.setContent { AppTheme { screen.content(vm) } }
        val controls = compose.onAllNodes(hasClickAction() or isToggleable()).fetchSemanticsNodes()
        assertTrue("${screen.name}: no controls found", controls.isNotEmpty())
        val unnamed = controls.filter { name(it).isBlank() }
        assertTrue("${screen.name}: unnamed controls ${unnamed.map { it.config }}", unnamed.isEmpty())
    }

    private fun name(n: SemanticsNode): String = listOfNotNull(
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        n.config.getOrNull(SemanticsProperties.EditableText)?.text,
    ).joinToString(" ")

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun cases() = screens.map { arrayOf<Any>(it) }
    }
}
