package eu.stedb.mysyncthing.ui

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import eu.stedb.mysyncthing.R
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
import androidx.compose.runtime.remember
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
 * paused, starts with the default patterns, and saving starts its first sync.
 * A null [id] edits the default patterns for new folders. Leaving with
 * unsaved edits, or with the folder still paused, asks first.
 */
@Composable
fun IgnoresScreen(s: Snapshot, id: String?, firstSync: Boolean, vm: MainViewModel, onBack: () -> Unit) {
    val f = id?.let { s.folders.find { f -> f.id == it } }
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var original by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    // Not saved: a recreated screen is no longer waiting for the save, which still finishes in the ViewModel.
    var saving by remember { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A folder added a moment ago may not be in the config yet.
    LaunchedEffect(id, f != null) {
        if (loaded || (id != null && f == null)) return@LaunchedEffect
        // A new folder has no patterns yet; with "patterns first" it starts from the defaults.
        val lines = if (id == null) vm.defaultIgnores()
        else vm.ignores(id)?.let { own -> if (own.isEmpty() && firstSync) vm.defaultIgnores() ?: own else own }
        lines?.let { original = it.joinToString("\n"); text = TextFieldValue(original); loaded = true }
    }
    val ask = firstSync || (loaded && text.text != original)
    BackHandler(enabled = ask) { leaving = true }

    AppScreen(
        title = stringResource(if (id == null) R.string.ignores_default_title else R.string.ignore_patterns),
        onBack = { if (ask) leaving = true else onBack() },
        actions = {
            IconButton(onClick = { help = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.ignores_help_title)) }
            TextButton(enabled = loaded && !saving, onClick = {
                saving = true
                scope.launch {
                    val lines = text.text.lines().map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
                    val ok = if (id == null) vm.saveDefaultIgnores(lines) else vm.saveIgnores(id, lines, resume = firstSync)
                    if (ok) onBack() else saving = false
                }
            }) { Text(stringResource(if (firstSync) R.string.ignores_save_and_sync else R.string.action_save)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
            Text(
                if (id == null) stringResource(R.string.ignores_default_intro)
                else (f?.label?.ifEmpty { null } ?: id).let { if (firstSync) stringResource(R.string.ignores_first_sync, it) else it },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LINE_PREFIXES.forEach { (prefix, @StringRes label) ->
                    AssistChip(onClick = { text = text.prefixLine(prefix) }, enabled = loaded, label = { Text("$prefix  ${stringResource(label)}") }, shape = ButtonShape)
                }
                AssistChip(onClick = { text = text.insert("**") }, enabled = loaded, label = { Text("**  ${stringResource(R.string.ignores_chip_any_path)}") }, shape = ButtonShape)
            }
            OutlinedTextField(
                text, { text = it }, enabled = loaded,
                placeholder = { Text(stringResource(if (loaded) R.string.ignores_placeholder else R.string.loading)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp),
            )
        }
    }
    if (help) IgnoresHelp { help = false }
    if (leaving) {
        ConfirmDialog(
            stringResource(if (firstSync) R.string.ignores_leave_title else R.string.discard_title),
            stringResource(if (firstSync) R.string.ignores_leave_text else R.string.ignores_discard_text),
            stringResource(if (firstSync) R.string.action_leave else R.string.action_discard),
            onDismiss = { leaving = false },
        ) { leaving = false; onBack() }
    }
}

private val LINE_PREFIXES = listOf(
    "!" to R.string.ignores_chip_keep, "(?i)" to R.string.ignores_chip_any_case,
    "(?d)" to R.string.ignores_chip_deletable, "//" to R.string.ignores_chip_comment,
)

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
        "name" to R.string.ignores_help_name,
        "/name" to R.string.ignores_help_top,
        "*.tmp" to R.string.ignores_help_star,
        "/photos/**" to R.string.ignores_help_double_star,
        "!keep.txt" to R.string.ignores_help_keep,
        "(?i)thumbs.db" to R.string.ignores_help_case,
        "(?d).DS_Store" to R.string.ignores_help_deletable,
        "// note" to R.string.ignores_help_comment,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ignores_help_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ignores_help_intro))
                rules.forEach { (pattern, meaning) ->
                    Column {
                        Text(pattern, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                        Text(stringResource(meaning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    stringResource(R.string.ignores_help_not_shared),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
