@file:OptIn(ExperimentalMaterial3Api::class)

package eu.stedb.mysyncthing.ui

import android.animation.ValueAnimator
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.SyncState
import eu.stedb.mysyncthing.core.Summary

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
            when (state) {
                is CoreState.Failed -> Centered { Text("Syncthing failed: ${state.message}", color = MaterialTheme.colorScheme.error) }
                else -> Centered { BusyIndicator(); Text("Starting Syncthing") }
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

/** A screen with a top bar on the app's tinted background. [onBack] adds a back arrow. */
@Composable
fun AppScreen(
    title: String,
    onBack: (() -> Unit)? = null,
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
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
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

/** The notification's status line as a compact chip for top bars. */
@Composable
fun StatusChip(summary: Summary, modifier: Modifier = Modifier) {
    val look = summary.state.look()
    Surface(
        modifier = modifier.padding(end = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(look.icon, null, Modifier.size(16.dp), tint = look.color)
            Spacer(Modifier.width(6.dp))
            Text(
                summary.text, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
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
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon?.let { { Icon(it, null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant) } },
        headlineContent = { Text(title, color = color) },
        supportingContent = value?.let { { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) } },
        trailingContent = trailing,
    )
}

/** Shown instead of an empty list: what the list holds and how to fill it. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String) {
    Centered {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 400.dp),
        )
    }
}

/** Centred in the space; scrolls when that is too short, as in landscape. */
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
    options: List<Pair<T, String>>,
    selected: T,
    describe: (T) -> String? = { null },
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(value == selected, role = Role.RadioButton) { onPick(value) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(value == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(label)
                            describe(value)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Edits one text value; [valid] gates the Save button. */
@Composable
fun TextDialog(
    title: String,
    initial: String,
    label: String,
    singleLine: Boolean = true,
    supporting: String? = null,
    valid: (String) -> Boolean = { it.isNotBlank() },
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text(label) }, singleLine = singleLine,
                minLines = if (singleLine) 1 else 6,
                supportingText = supporting?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = valid(text)) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
