package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.SyncthingCore

private const val AGPL = "https://www.gnu.org/licenses/agpl-3.0.html"
private const val MPL = "https://www.mozilla.org/en-US/MPL/2.0/"
private const val APACHE = "https://www.apache.org/licenses/LICENSE-2.0"
private const val SOURCE = "https://github.com/aiste633/mysyncthing"
private const val DOCS = "https://docs.syncthing.net/"

/** Libraries in the APK, with their licences; Syncthing's Go dependencies are covered by its own notice. */
private val LIBRARIES = listOf(
    "AndroidX and Jetpack Compose" to "Apache-2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache-2.0",
    "OkHttp, Okio" to "Apache-2.0",
    "ZXing and ZXing Android Embedded" to "Apache-2.0",
    "Syncthing and the Go libraries it uses" to "MPL-2.0, with MIT, BSD and Apache-2.0 parts",
)

/** App and core versions, licences and links, as F-Droid users expect. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    var libraries by rememberSaveable { mutableStateOf(false) }
    val link = @Composable { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }

    AppScreen(title = "About", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SettingsGroup("MySyncthing") {
                SettingRow("Version", version)
                SettingRow("Licence", "AGPL-3.0", trailing = link) { uri.openUri(AGPL) }
                SettingRow("Source code", SOURCE.removePrefix("https://"), trailing = link) { uri.openUri(SOURCE) }
            }
            SettingsGroup("Syncthing") {
                SettingRow("Version", SyncthingCore.version)
                SettingRow("Licence", "MPL-2.0", trailing = link) { uri.openUri(MPL) }
                SettingRow("Documentation", DOCS.removePrefix("https://").trimEnd('/'), trailing = link) { uri.openUri(DOCS) }
            }
            SettingsGroup("Other") {
                SettingRow("Open-source licences", "Libraries used by the app") { libraries = true }
            }
        }
    }
    if (libraries) {
        AlertDialog(
            onDismissRequest = { libraries = false },
            title = { Text("Open-source licences") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LIBRARIES.forEach { (name, licence) -> SettingRow(name, licence) }
                    SettingRow("Apache License 2.0", "Full text", trailing = link) { uri.openUri(APACHE) }
                }
            },
            confirmButton = { TextButton(onClick = { libraries = false }) { Text("Close") } },
        )
    }
}
