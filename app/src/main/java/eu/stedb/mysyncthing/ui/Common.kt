@file:OptIn(ExperimentalMaterial3Api::class)

package eu.stedb.mysyncthing.ui

import android.animation.ValueAnimator
import android.net.Uri
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.R as CoreR
import eu.stedb.mysyncthing.core.Summary
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.SyncthingCore
import eu.stedb.mysyncthing.core.SyncthingService
import eu.stedb.mysyncthing.core.SystemError
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.problemText

/**
 * System bars plus the camera cutout, without the keyboard. Material's bars only
 * avoid the system bars by default, so in landscape their buttons and the list
 * edges would sit under the cutout (edge-to-edge is enforced from API 35).
 */
val SafeBars: WindowInsets @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** Shows [content] only while the core runs; otherwise its state. */
@Composable
fun CoreGate(state: CoreState, content: @Composable () -> Unit) {
    if (state is CoreState.Running) return content()
    // Not inside an AppScreen, so it sets its own background and insets.
    Surface(Modifier.fillMaxSize(), color = AppColors.background) {
        Column(Modifier.safeDrawingPadding()) {
            val context = LocalContext.current
            val stoppedByUser by SyncthingCore.stoppedByUser.collectAsStateWithLifecycle()
            when (state) {
                is CoreState.Failed -> Centered {
                    Text(stringResource(R.string.core_failed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    val why = if (state.message.isEmpty()) stringResource(R.string.core_stopped_unexpectedly) else problemText(state.message).asString()
                    Text(why.replaceFirstChar { it.uppercaseChar() }, textAlign = TextAlign.Center)
                    Button(shape = ButtonShape, onClick = { SyncthingService.start(context) }) { Text(stringResource(R.string.action_try_again)) }
                }
                // Otherwise a start is on its way (the app just opened, or an import restarts it).
                CoreState.Stopped if stoppedByUser -> Centered {
                    Text(stringResource(R.string.core_stopped))
                    Button(shape = ButtonShape, onClick = { SyncthingService.start(context) }) { Text(stringResource(R.string.action_start)) }
                }
                else -> Centered { BusyIndicator(); Text(stringResource(R.string.core_starting)) }
            }
        }
    }
}

/**
 * A spinner, or a static icon when the phone has animations turned off: the
 * spinner then freezes on its first frame, a dot that reads as nothing.
 */
@Composable
fun BusyIndicator() {
    val animated = remember { ValueAnimator.areAnimatorsEnabled() }
    if (animated) CircularProgressIndicator()
    else Icon(Icons.Default.HourglassTop, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
}

/**
 * A screen with a top bar on the app's tinted background. [onBack] adds a back
 * arrow; [status] adds the status chip after the title.
 */
@Composable
fun AppScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    status: Summary? = null,
    actions: @Composable () -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = AppColors.background,
        // Content and FAB keep clear of the bars, the cutout and the keyboard.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    if (status == null) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // In the title slot, not the actions: the bar sizes actions first, so a long
                    // status cut the title ("Folde…"). Here the title keeps its width and the chip gets the rest.
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusChip(status, Modifier.weight(1f, fill = false).padding(start = 12.dp))
                    }
                },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.background),
                windowInsets = SafeBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
        },
        // Scaffold lifts the FAB above the bottom insets only; in landscape the side
        // navigation bar would cover it.
        floatingActionButton = { Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))) { fab() } },
        content = content,
    )
}

/**
 * A plain bar: Material's default adds a gap and a dot at the track's end,
 * which reads as a glitch, and rounded caps.
 */
@Composable
fun ProgressBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress }, modifier = modifier.fillMaxWidth(), color = color,
        strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {},
    )
}

/** The notification's status line as a compact chip for top bars. */
@Composable
fun StatusChip(summary: Summary, modifier: Modifier = Modifier) {
    val look = summary.state.look()
    Surface(
        modifier = modifier.padding(end = 4.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(look.icon, null, Modifier.size(16.dp), tint = look.color)
            Spacer(Modifier.width(6.dp))
            Text(
                summary.text.asString(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A list card: outline plus card colour, full width with list margins. */
@Composable
fun ListCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, colors = AppColors.card(), border = AppColors.cardBorder(), content = content)
    } else {
        Card(modifier = modifier, colors = AppColors.card(), border = AppColors.cardBorder(), content = content)
    }
}

/** Highlighted card for something waiting on the user, with the bell icon. */
@Composable
fun RequestCard(title: String, detail: String?, actions: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        val look = SyncState.NeedsInput.look()
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), verticalAlignment = Alignment.Top) {
            Icon(look.icon, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) { actions() }
    }
}

/** The core's notices, in red, newest first, until the user clears them. */
@Composable
fun ProblemsCard(errors: List<SystemError>, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        val fg = MaterialTheme.colorScheme.onErrorContainer
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Error, null, tint = fg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    pluralStringResource(CoreR.plurals.status_problems, errors.size, errors.size),
                    style = MaterialTheme.typography.titleSmall, color = fg,
                )
                errors.reversed().forEach { e ->
                    Text(
                        listOfNotNull(whenText(e.time)?.asString(), e.text).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = fg,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClear) { Text(stringResource(CoreR.string.action_clear), color = fg) }
        }
    }
}

/** Card group of settings rows under a heading, like system Settings. */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 6.dp),
    )
    ListCard(content = content)
}

/** One row in a [SettingsGroup]. The whole row is the touch target. */
@Composable
fun SettingRow(
    title: String,
    value: String? = null,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) = SettingRowLayout(title, value, icon, trailing, if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier, danger)

/**
 * A row with a switch. The row is the toggle, so accessibility services read
 * it as a switch with its on/off state; the switch itself only draws it.
 */
@Composable
fun SwitchRow(title: String, value: String?, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) =
    SettingRowLayout(
        title, value, icon, trailing = { Switch(checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
    )

/** Like [SwitchRow], with a checkbox: for picking items from a list. */
@Composable
fun CheckRow(title: String, value: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingRowLayout(
        title, value, icon = null, trailing = { Checkbox(checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
    )

/** Like [CheckRow], with a radio button: one choice of several. */
@Composable
fun RadioRow(title: String, value: String?, selected: Boolean, onSelect: () -> Unit) =
    SettingRowLayout(
        title, value, icon = null, trailing = { RadioButton(selected, onClick = null) },
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect),
    )

@Composable
private fun SettingRowLayout(
    title: String,
    value: String?,
    icon: ImageVector?,
    trailing: @Composable (() -> Unit)?,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon?.let { { Icon(it, null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant) } },
        headlineContent = { Text(title, color = color) },
        // No line limit: hints are sentences, and a cut one can't be read anywhere else.
        supportingContent = value?.let { { Text(it) } },
        trailingContent = trailing,
    )
}

/**
 * The floating Add button of a tab. Built on the content overload: the
 * icon-and-text one clears the text from accessibility (it expects the icon to
 * carry the name), which left TalkBack saying only "Button".
 */
@Composable
fun AddFab(label: String, onClick: () -> Unit) = ExtendedFloatingActionButton(onClick = onClick) {
    Icon(Icons.Default.Add, null)
    Spacer(Modifier.width(12.dp))
    Text(label)
}

/** Shown instead of an empty list: what the list holds and how to fill it. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String) {
    Centered { EmptyHint(icon, title, hint) }
}

/**
 * The empty-state hint without centring or scrolling, for a place that already scrolls,
 * such as a lazy list item: [Centered] needs a bounded height, which a list item has not.
 */
@Composable
fun EmptyHint(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 400.dp),
        )
    }
}

/** Centred in the space; scrolls when that is too short, as in landscape. Needs a bounded height. */
@Composable
private fun Centered(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

/** Single-choice dialog with radio rows and an optional explanation per choice. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, UiText>>,
    selected: T,
    describe: (T) -> UiText? = { null },
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { ChoiceList(options, selected, Modifier.verticalScroll(rememberScrollState()), describe, onPick) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Radio rows with an optional explanation each; in [ChoiceDialog] and inline in forms. */
@Composable
fun <T> ChoiceList(
    options: List<Pair<T, UiText>>,
    selected: T,
    modifier: Modifier = Modifier,
    describe: (T) -> UiText? = { null },
    onPick: (T) -> Unit,
) {
    Column(modifier) {
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(value == selected, role = Role.RadioButton) { onPick(value) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(value == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(label.asString())
                    describe(value)?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/**
 * Edits one text value. [error] says why a value can't be saved; it then
 * replaces [supporting] under the field and Save is off.
 */
@Composable
fun TextDialog(
    title: String,
    initial: String,
    label: String,
    singleLine: Boolean = true,
    supporting: String? = null,
    error: (String) -> UiText? = ::requiredError,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val problem = error(text)?.asString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text(label) }, singleLine = singleLine,
                minLines = if (singleLine) 1 else 6, isError = problem != null,
                supportingText = (problem ?: supporting)?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = problem == null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Back (system or arrow) that asks before dropping a form the user [changed].
 * Returns the action for the top bar's arrow.
 */
@Composable
fun rememberDiscardGuard(changed: Boolean, onBack: () -> Unit): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = changed) { asking = true }
    if (asking) {
        ConfirmDialog(
            stringResource(R.string.discard_title), stringResource(R.string.discard_text), stringResource(R.string.action_discard), onDismiss = { asking = false }) {
            asking = false
            onBack()
        }
    }
    return { if (changed) asking = true else onBack() }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Folder picker returning a POSIX path. With All files access the core uses
 * paths directly, so the SAF tree URI is only used to name the directory.
 */
@Composable
fun rememberFolderPicker(onPath: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::treeUriToPath)?.let(onPath)
    }
    return { launcher.launch(null) }
}

fun treeUriToPath(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = DocumentsContract.getTreeDocumentId(uri)
    val volume = docId.substringBefore(':')
    val rel = docId.substringAfter(':', "")
    val root = if (volume == "primary") INTERNAL_STORAGE else "/storage/$volume"
    return if (rel.isEmpty()) root else "$root/$rel"
}

const val INTERNAL_STORAGE = "/storage/emulated/0"

/** Heading over a group of cards on a tab (folder or device groups). */
@Composable
fun GroupTitle(name: String) = Text(
    name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
    modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
)

/** Items without a group first (key ""), then each group by name; groups differing only in case stay apart. */
fun <T> grouped(items: List<T>, group: (T) -> String): Map<String, List<T>> =
    items.groupBy { group(it).trim() }.toSortedMap(String.CASE_INSENSITIVE_ORDER.thenBy { it })

/** A speed limit in kB/s or MB/s; empty or 0 means no limit. Returns KiB/s, as the core stores it. */
@Composable
fun SpeedLimitDialog(title: String, kib: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val (v0, u0) = limitSi(kib)
    val locale = currentStrings().locale
    var value by rememberSaveable { mutableStateOf(if (kib > 0) fieldNumber(v0, locale) else "") }
    var unit by rememberSaveable { mutableStateOf(u0) }
    val units = listOf("kB/s", "MB/s")
    val error = amountError(value, unit, emptyOk = true)?.asString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value, { value = it }, label = { Text(stringResource(R.string.limit_label)) }, placeholder = { Text(stringResource(R.string.limit_none)) },
                    singleLine = true, isError = error != null, supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    units.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = unit == u, onClick = { unit = u }, icon = {},
                            shape = SegmentedButtonDefaults.itemShape(i, units.size, MaterialTheme.shapes.extraSmall),
                        ) { Text(u) }
                    }
                }
                Text(stringResource(R.string.limit_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(limitKib(decimal(value) ?: 0.0, unit)) }, enabled = error == null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * A list setting where one [token] ("dynamic", "default") stands for
 * Syncthing's automatic choice, shown as a checkbox, plus entries of the
 * user's own. Saving needs at least one of them.
 */
@Composable
fun ListEditDialog(
    title: String,
    initial: List<String>,
    token: String,
    tokenTitle: String,
    tokenHint: String,
    addHint: String,
    valid: (String) -> Boolean,
    normalize: (String) -> String = { it.trim() },
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var auto by rememberSaveable { mutableStateOf(initial.isEmpty() || token in initial) }
    var own by rememberSaveable { mutableStateOf(initial.filter { it != token }) }
    var draft by rememberSaveable { mutableStateOf("") }
    val next = normalize(draft)
    val draftValid = valid(next)
    // A typed but not yet added entry counts too, so it is not lost.
    val all = (if (auto) listOf(token) else emptyList()) + (own + listOfNotNull(next.takeIf { draftValid })).distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().toggleable(auto, role = Role.Checkbox) { auto = it },
                ) {
                    Checkbox(auto, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text(tokenTitle, style = MaterialTheme.typography.bodyLarge)
                        Text(tokenHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                own.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(a, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        IconButton(onClick = { own = own - a }) { Icon(Icons.Default.Close, stringResource(R.string.action_remove_item, a)) }
                    }
                }
                OutlinedTextField(
                    draft, { draft = it }, label = { Text(stringResource(R.string.action_add)) }, singleLine = true,
                    isError = (draft.isNotBlank() && !draftValid) || all.isEmpty(),
                    // Save is off with nothing in the list, so say why instead of the format hint.
                    supportingText = { Text(if (all.isEmpty()) stringResource(R.string.list_add_or_tick, tokenTitle) else addHint) },
                    trailingIcon = {
                        IconButton(enabled = draftValid, onClick = { own = (own + next).distinct(); draft = "" }) { Icon(Icons.Default.Add, stringResource(R.string.action_add)) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(all) }, enabled = all.isNotEmpty() && (draft.isBlank() || draftValid)) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The show/hide button of a password field. While the password shows as plain text, its window
 * (the screen's, or a dialog's) is kept out of screenshots, screen recordings and Recents.
 */
@Composable
fun PasswordVisibilityButton(show: Boolean, onToggle: () -> Unit) {
    val view = LocalView.current
    val activity = LocalActivity.current
    DisposableEffect(show, view, activity) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: activity?.window
        if (show) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (show) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    IconButton(onClick = onToggle) {
        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (show) R.string.password_hide else R.string.password_show))
    }
}
