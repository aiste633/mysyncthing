package eu.stedb.mysyncthing.ui

import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import eu.stedb.mysyncthing.R
import kotlin.math.max

/** A window less tall than this is a phone on its side (or a short split screen): [EntryDialog] fills it. */
const val SHORT_WINDOW_DP = 480

/**
 * The dialogs with a text field: [title], [content], Cancel and [confirm] ([confirmEnabled]).
 *
 * Upright it is Material's AlertDialog, drawn the same: the layout below is Material's, as AlertDialog
 * keeps it internal. In a window under [SHORT_WINDOW_DP] tall (every phone on its side) the keyboard
 * covered most of that dialog, Save included: there it fills the window, with ✕ (Cancel), the title
 * and a filled [confirm] in a top bar, [content] scrolling under it, and the window resizes above the
 * keyboard. Back is Cancel in both.
 *
 * Both are one dialog window: turning the phone only changes the window's properties (its width) and
 * this layout, in place. Another dialog call for the other look would remove the window and add a new
 * one in the middle of the rotation, which on Android 15 could leave a window that never gets input
 * again (#68). [content] keeps its place in the tree, so a focused field keeps its cursor and the
 * keyboard; what is typed lives in the caller's rememberSaveable. [secure] keeps the window out of screenshots,
 * screen recordings and Recents (a password shown as plain text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDialog(
    title: String,
    confirm: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    secure: Boolean = false,
    content: @Composable () -> Unit,
) {
    // The window the app draws in, as it is now (Configuration.screenHeightDp rounds, and its insets depend on the target SDK).
    val short = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < SHORT_WINDOW_DP.dp
    val scroll = rememberScrollState()
    // Counts the changes of the space under the bar: after a rotation the keyboard hides and shows again
    // within a frame, so the height alone can end where it was while the scroll went back to the top.
    val viewportChanges = remember { mutableIntStateOf(0) }
    val window = remember { arrayOfNulls<Window>(1) }
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        // Over Material's 560 dp cap on a dialog's width (BasicAlertDialog applies it after this).
        modifier = if (short) Modifier.fillMaxSize() else Modifier,
        // [secure] here, not only on the window: the dialog sets the window's FLAG_SECURE from its properties at
        // each update (a rotation is one), which cleared a flag set on the window from outside.
        properties = DialogProperties(securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit, usePlatformDefaultWidth = !short),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window[0] = dialogWindow
            dialogWindow?.fitKeyboard(short)
        }
        Surface(
            shape = if (short) RectangleShape else AlertDialogDefaults.shape,
            color = if (short) AppColors.background else AlertDialogDefaults.containerColor,
            tonalElevation = if (short) 0.dp else AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(if (short) PaddingValues() else DialogPadding)) {
                if (short) {
                    ShortBar(title, confirm, confirmEnabled, onConfirm, onDismiss)
                } else {
                    ProvideContentColorTextStyle(AlertDialogDefaults.titleContentColor, MaterialTheme.typography.headlineSmall) {
                        Box(Modifier.padding(TitlePadding).align(Alignment.Start)) { Text(title) }
                    }
                }
                // The same chain of modifiers in both looks, only other values: the field inside stays as it is.
                ProvideContentColorTextStyle(AlertDialogDefaults.textContentColor, MaterialTheme.typography.bodyMedium) {
                    Box(
                        Modifier.weight(1f, fill = short)
                            .padding(if (short) PaddingValues() else TextPadding)
                            .onSizeChanged { viewportChanges.intValue++ }
                            .verticalScroll(scroll)
                            .widthIn(max = if (short) ShortContentMaxWidth else Dp.Unspecified)
                            .padding(if (short) ShortContentPadding else PaddingValues())
                            .align(Alignment.Start),
                    ) { CompositionLocalProvider(LocalShortViewport provides if (short) viewportChanges else null, content = content) }
                }
                if (!short) {
                    Box(Modifier.align(Alignment.End)) {
                        ProvideContentColorTextStyle(MaterialTheme.colorScheme.primary, MaterialTheme.typography.labelLarge) {
                            AlertDialogFlowRow(ButtonsMainAxisSpacing, ButtonsCrossAxisSpacing) {
                                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                                TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirm) }
                            }
                        }
                    }
                }
            }
        }
    }
    // After the dialog's own SideEffect above, which sets the window's soft input mode (Android then
    // pans, which pushed the bar off the top for a field further down): this one runs after it in every
    // composition, and the one in the content when the window's content is first composed.
    SideEffect { window[0]?.fitKeyboard(short) }
}

/**
 * Resizes the window above the keyboard when [short]. Upright the window keeps what Compose's Dialog
 * leaves (unspecified, and Android pans these dialogs); back from [short] it pans again, set outright, as
 * an unspecified mode keeps the last one (ViewRootImpl) and Window.setSoftInputMode(0), what the Dialog
 * calls, changes nothing. Android's replacement for ADJUST_RESIZE (no decor fitting, the keyboard's insets
 * handled in Compose) is set once when the Dialog makes its window; changing it would take a new window,
 * which is what #68 is about.
 */
@Suppress("DEPRECATION")
private fun Window.fitKeyboard(short: Boolean) {
    val resize = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    val mode = when {
        short -> resize
        attributes.softInputMode == resize -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        else -> return
    }
    if (attributes.softInputMode != mode) attributes = attributes.apply { softInputMode = mode }
}

/** In a short [EntryDialog]: how often the space under its bar changed size (the keyboard, a rotation). Null upright. */
private val LocalShortViewport = compositionLocalOf<State<Int>?> { null }

/**
 * A text field of an [EntryDialog]: on a phone on its side, kept whole in view (label, field and the text
 * under it) while it has focus, also when the keyboard or a rotation shrinks the space under the bar.
 * Compose keeps a focused child in view on such a resize only when the child reports its focused bounds,
 * which value/onValueChange text fields don't: at the largest font the field's lower part stayed under
 * the keyboard. Upright it does nothing (Android pans the dialog there).
 */
@Composable
fun Modifier.inViewWhenFocused(): Modifier {
    val viewport = LocalShortViewport.current
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val changes = viewport?.value
    LaunchedEffect(focused, changes) { if (focused && changes != null) requester.bringIntoView() }
    return bringIntoViewRequester(requester).onFocusChanged { focused = it.hasFocus }
}

/** ✕ for Cancel, the title on one line, and the filled [confirm] at the end. */
@Composable
private fun ShortBar(title: String, confirm: String, confirmEnabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel)) }
            Text(
                title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 16.dp),
            )
            Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirm) }
        }
        HorizontalDivider()
    }
}

/** Under the bar: in line with the title, and no wider than a dialog's text needs. */
private val ShortContentPadding = PaddingValues(start = 64.dp, end = 24.dp, top = 16.dp, bottom = 24.dp)
private val ShortContentMaxWidth = 64.dp + 560.dp + 24.dp

// From here on as in Material 3's AlertDialog.kt (androidx.compose.material3 1.4.0, AlertDialogContent and
// AlertDialogFlowRow, internal there). Copyright 2021 The Android Open Source Project, Apache License 2.0.
private val DialogPadding = PaddingValues(all = 24.dp)
private val TitlePadding = PaddingValues(bottom = 16.dp)
private val TextPadding = PaddingValues(bottom = 24.dp)
private val ButtonsMainAxisSpacing = 8.dp
private val ButtonsCrossAxisSpacing = 12.dp

@Composable
private fun ProvideContentColorTextStyle(contentColor: Color, textStyle: TextStyle, content: @Composable () -> Unit) {
    val mergedStyle = LocalTextStyle.current.merge(textStyle)
    CompositionLocalProvider(LocalContentColor provides contentColor, LocalTextStyle provides mergedStyle, content = content)
}

/** The buttons in a row at the end, or stacked (confirm on top) when they don't fit. */
@Composable
private fun AlertDialogFlowRow(mainAxisSpacing: Dp, crossAxisSpacing: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val sequences = mutableListOf<List<Placeable>>()
        val crossAxisPositions = mutableListOf<Int>()
        var mainAxisSpace = 0
        var crossAxisSpace = 0
        val currentSequence = mutableListOf<Placeable>()
        var currentMainAxisSize = 0
        var currentCrossAxisSize = 0

        fun canAddToCurrentSequence(placeable: Placeable) =
            currentSequence.isEmpty() || currentMainAxisSize + mainAxisSpacing.roundToPx() + placeable.width <= constraints.maxWidth

        fun startNewSequence() {
            if (sequences.isNotEmpty()) crossAxisSpace += crossAxisSpacing.roundToPx()
            // Confirming actions above dismissive ones.
            sequences.add(0, currentSequence.toList())
            crossAxisPositions += crossAxisSpace
            crossAxisSpace += currentCrossAxisSize
            mainAxisSpace = max(mainAxisSpace, currentMainAxisSize)
            currentSequence.clear()
            currentMainAxisSize = 0
            currentCrossAxisSize = 0
        }

        for (measurable in measurables) {
            val placeable = measurable.measure(constraints)
            if (!canAddToCurrentSequence(placeable)) startNewSequence()
            if (currentSequence.isNotEmpty()) currentMainAxisSize += mainAxisSpacing.roundToPx()
            currentSequence.add(placeable)
            currentMainAxisSize += placeable.width
            currentCrossAxisSize = max(currentCrossAxisSize, placeable.height)
        }
        if (currentSequence.isNotEmpty()) startNewSequence()

        val mainAxisLayoutSize = max(mainAxisSpace, constraints.minWidth)
        val crossAxisLayoutSize = max(crossAxisSpace, constraints.minHeight)
        layout(mainAxisLayoutSize, crossAxisLayoutSize) {
            sequences.forEachIndexed { i, placeables ->
                val childrenMainAxisSizes = IntArray(placeables.size) { j ->
                    placeables[j].width + if (j < placeables.lastIndex) mainAxisSpacing.roundToPx() else 0
                }
                val mainAxisPositions = IntArray(childrenMainAxisSizes.size)
                with(Arrangement.End) { arrange(mainAxisLayoutSize, childrenMainAxisSizes, layoutDirection, mainAxisPositions) }
                placeables.forEachIndexed { j, placeable -> placeable.place(x = mainAxisPositions[j], y = crossAxisPositions[i]) }
            }
        }
    }
}
