package eu.stedb.mysyncthing.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.Snapshot
import kotlinx.coroutines.launch

/**
 * The folder's .stignore as plain text, with chips for the syntax that is
 * awkward to type on a phone. With [firstSync] the folder was just added
 * paused, and saving starts its first sync. Leaving with unsaved edits, or
 * with the folder still paused, asks first.
 */
@Composable
fun IgnoresScreen(s: Snapshot, id: String, firstSync: Boolean, vm: MainViewModel, onBack: () -> Unit) {
    val f = s.folders.find { it.id == id }
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var original by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A folder added a moment ago may not be in the config yet.
    LaunchedEffect(id, f != null) {
        if (f == null || loaded) return@LaunchedEffect
        vm.ignores(id)?.let { original = it.joinToString("\n"); text = TextFieldValue(original); loaded = true }
    }
    val ask = firstSync || (loaded && text.text != original)
    BackHandler(enabled = ask) { leaving = true }

    AppScreen(
        title = "Ignore patterns",
        onBack = { if (ask) leaving = true else onBack() },
        actions = {
            IconButton(onClick = { help = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "How patterns work") }
            TextButton(enabled = loaded && !saving, onClick = {
                saving = true
                scope.launch {
                    val lines = text.text.lines().map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
                    if (vm.saveIgnores(id, lines, resume = firstSync)) onBack() else saving = false
                }
            }) { Text(if (firstSync) "Save and sync" else "Save") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
            Text(
                (f?.label?.ifEmpty { null } ?: id) +
                    if (firstSync) ". The folder stays paused until you save, so nothing is synced before the patterns apply." else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LINE_PREFIXES.forEach { (prefix, label) ->
                    AssistChip(onClick = { text = text.prefixLine(prefix) }, enabled = loaded, label = { Text("$prefix  $label") }, shape = ButtonShape)
                }
                AssistChip(onClick = { text = text.insert("**") }, enabled = loaded, label = { Text("**  any path") }, shape = ButtonShape)
            }
            OutlinedTextField(
                text, { text = it }, enabled = loaded,
                placeholder = { Text(if (loaded) "One pattern per line" else "Loading") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp),
            )
        }
    }
    if (help) IgnoresHelp { help = false }
    if (leaving) {
        ConfirmDialog(
            if (firstSync) "Leave without saving?" else "Discard changes?",
            if (firstSync) "The folder stays paused. Resume it on its screen when you are ready." else "Your edits to the patterns are lost.",
            if (firstSync) "Leave" else "Discard",
            onDismiss = { leaving = false },
        ) { leaving = false; onBack() }
    }
}

private val LINE_PREFIXES = listOf("!" to "keep", "(?i)" to "any case", "(?d)" to "deletable", "//" to "comment")

/** Puts [prefix] at the start of the line holding the cursor, unless it is already there. */
private fun TextFieldValue.prefixLine(prefix: String): TextFieldValue {
    val start = text.lastIndexOf('\n', (selection.min - 1).coerceAtLeast(-1)) + 1
    if (text.startsWith(prefix, start)) return this
    return copy(
        text = text.substring(0, start) + prefix + text.substring(start),
        selection = TextRange(selection.start + prefix.length, selection.end + prefix.length),
    )
}

/** Replaces the selection with [s] and puts the cursor after it. */
private fun TextFieldValue.insert(s: String): TextFieldValue {
    val at = selection.min
    return copy(text = text.substring(0, at) + s + text.substring(selection.max), selection = TextRange(at + s.length))
}

@Composable
private fun IgnoresHelp(onDismiss: () -> Unit) {
    val rules = listOf(
        "name" to "Ignores every file or folder called name, at any depth.",
        "/name" to "Only name at the top of this folder.",
        "*.tmp" to "* matches anything except /; ? matches one character.",
        "/photos/**" to "** matches anything, including /.",
        "!keep.txt" to "Syncs it after all. The first line that matches wins, so put it above the broader rule.",
        "(?i)thumbs.db" to "Ignores upper and lower case.",
        "(?d).DS_Store" to "Syncthing may delete it when it is all that keeps a deleted folder from going away.",
        "// note" to "A comment.",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How patterns work") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("One pattern per line, relative to this folder. Ignored files stay on this phone but are not synced.")
                rules.forEach { (pattern, meaning) ->
                    Column {
                        Text(pattern, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                        Text(meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "Patterns are stored in the folder's .stignore file and are not shared: set them on each device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
