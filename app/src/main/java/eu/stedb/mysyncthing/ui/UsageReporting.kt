package eu.stedb.mysyncthing.ui

import androidx.compose.ui.res.stringResource
import eu.stedb.mysyncthing.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.stedb.mysyncthing.core.CoreState
import kotlinx.coroutines.launch

private const val STATS = "https://data.syncthing.net/"

/** Where the link goes in usage_stats; a character no translation holds. */
private const val LINK_MARK = "\uE000"

/** Upstream's consent state: whether to ask now, and whether reporting is on. */
data class UsageConsent(val needsPrompt: Boolean, val enabled: Boolean)

/**
 * Upstream's "Allow anonymous usage reporting?" question, asked natively the
 * first time the core runs, with upstream's wording and condition. Answering
 * here also stops the web GUI from asking. It asks again only if upstream
 * changes the report format, as upstream does.
 */
@Composable
fun UsageReportingPrompt(core: CoreState, vm: MainViewModel) {
    var show by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.usage_preview_failed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(core is CoreState.Running) {
        if (core is CoreState.Running) show = vm.usageConsent()?.needsPrompt == true
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = {},
        // An answer is required, as in the web GUI; it is asked only once.
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.usage_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.usage_text))
                val (before, after) = stringResource(R.string.usage_stats, LINK_MARK).split(LINK_MARK).let { it.first() to it.last() }
                val link = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
                Text(
                    buildAnnotatedString {
                        append(before)
                        withLink(LinkAnnotation.Url(STATS, TextLinkStyles(link))) { append(STATS) }
                        append(after)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { scope.launch { preview = vm.usageReport() ?: failed } }) {
                    Text(stringResource(R.string.usage_preview))
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.answerUsagePrompt(true); show = false }) { Text(stringResource(R.string.action_yes)) } },
        dismissButton = { TextButton(onClick = { vm.answerUsagePrompt(false); show = false }) { Text(stringResource(R.string.action_no)) } },
    )
    preview?.let { report ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.usage_report)) },
            text = {
                SelectionContainer {
                    Text(
                        report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
