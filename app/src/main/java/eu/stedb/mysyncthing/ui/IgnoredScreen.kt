package eu.stedb.mysyncthing.ui

import androidx.compose.ui.res.stringResource
import eu.stedb.mysyncthing.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.IgnoredRequests
import kotlinx.coroutines.launch

/** Device and folder requests the user ignored, each with Unignore. */
@Composable
fun IgnoredScreen(vm: MainViewModel, onBack: () -> Unit) {
    var data by remember { mutableStateOf<IgnoredRequests?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { data = vm.ignoredRequests() }
    val scope = rememberCoroutineScope()
    val unignore = { device: String, folder: String? ->
        scope.launch { if (vm.unignore(device, folder)) reload++ }
        Unit
    }

    AppScreen(title = stringResource(R.string.settings_ignored), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            val d = data
            val folders = d?.devices.orEmpty().flatMap { dev -> dev.ignoredFolders.map { dev to it } }
            if (d != null && d.remoteIgnoredDevices.isEmpty() && folders.isEmpty()) {
                Note(stringResource(R.string.ignored_none))
            }
            if (d != null && d.remoteIgnoredDevices.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.tab_devices)) {
                    d.remoteIgnoredDevices.forEach { o ->
                        SettingRow(
                            o.name.ifEmpty { shortId(o.deviceID) },
                            listOfNotNull(stringResource(R.string.ignored_id, shortId(o.deviceID)), whenText(o.time)?.let { stringResource(R.string.ignored_when, it.asString()) }).joinToString(" · "),
                            trailing = { TextButton(onClick = { unignore(o.deviceID, null) }) { Text(stringResource(R.string.action_unignore)) } },
                        )
                    }
                }
            }
            if (folders.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.tab_folders)) {
                    folders.forEach { (dev, o) ->
                        SettingRow(
                            stringResource(R.string.ignored_folder, o.label.ifEmpty { o.id }, dev.name.ifEmpty { shortId(dev.deviceID) }),
                            whenText(o.time)?.let { stringResource(R.string.ignored_when_start, it.asString()) },
                            trailing = { TextButton(onClick = { unignore(dev.deviceID, o.id) }) { Text(stringResource(R.string.action_unignore)) } },
                        )
                    }
                }
            }
            if (d != null && (d.remoteIgnoredDevices.isNotEmpty() || folders.isNotEmpty())) {
                Note(stringResource(R.string.ignored_comes_back))
            }
        }
    }
}

@Composable
private fun Note(text: String) = Text(
    text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
)
