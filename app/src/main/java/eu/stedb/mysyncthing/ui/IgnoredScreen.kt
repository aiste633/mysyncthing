package eu.stedb.mysyncthing.ui

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

    AppScreen(title = "Ignored requests", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            val d = data
            val folders = d?.devices.orEmpty().flatMap { dev -> dev.ignoredFolders.map { dev to it } }
            if (d != null && d.remoteIgnoredDevices.isEmpty() && folders.isEmpty()) {
                Note("Nothing ignored. When you tap Ignore on a request, it is listed here.")
            }
            if (d != null && d.remoteIgnoredDevices.isNotEmpty()) {
                SettingsGroup("Devices") {
                    d.remoteIgnoredDevices.forEach { o ->
                        SettingRow(
                            o.name.ifEmpty { shortId(o.deviceID) },
                            listOfNotNull("ID ${shortId(o.deviceID)}", whenText(o.time)?.let { "ignored $it" }).joinToString(" · "),
                            trailing = { TextButton(onClick = { unignore(o.deviceID, null) }) { Text("Unignore") } },
                        )
                    }
                }
            }
            if (folders.isNotEmpty()) {
                SettingsGroup("Folders") {
                    folders.forEach { (dev, o) ->
                        SettingRow(
                            "\"${o.label.ifEmpty { o.id }}\" from ${dev.name.ifEmpty { shortId(dev.deviceID) }}",
                            whenText(o.time)?.let { "Ignored $it" },
                            trailing = { TextButton(onClick = { unignore(dev.deviceID, o.id) }) { Text("Unignore") } },
                        )
                    }
                }
            }
            if (d != null && (d.remoteIgnoredDevices.isNotEmpty() || folders.isNotEmpty())) {
                Note("The request comes back the next time that device connects.")
            }
        }
    }
}

@Composable
private fun Note(text: String) = Text(
    text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
)
