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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.SyncthingCore
import eu.stedb.mysyncthing.core.raw
import eu.stedb.mysyncthing.core.text
import eu.stedb.mysyncthing.core.R as CoreR

private const val AGPL = "https://www.gnu.org/licenses/agpl-3.0.html"
private const val MPL = "https://www.mozilla.org/en-US/MPL/2.0/"
private const val APACHE = "https://www.apache.org/licenses/LICENSE-2.0"
private const val SOURCE = "https://github.com/aiste633/mysyncthing"
private const val DOCS = "https://docs.syncthing.net/"

/** Libraries in the APK, with their licences; Syncthing's Go dependencies are covered by its own notice. */
private val LIBRARIES = listOf(
    text(R.string.lib_androidx) to raw("Apache-2.0"),
    raw("Kotlin, kotlinx.coroutines, kotlinx.serialization") to raw("Apache-2.0"),
    raw("OkHttp, Okio") to raw("Apache-2.0"),
    text(R.string.lib_zxing) to raw("Apache-2.0"),
    text(R.string.lib_syncthing) to text(R.string.lib_syncthing_licence),
)

/** App and core versions, licences and links, as F-Droid users expect. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    var libraries by rememberSaveable { mutableStateOf(false) }
    val link = @Composable { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }

    AppScreen(title = stringResource(R.string.about), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SettingsGroup(stringResource(R.string.app_name)) {
                SettingRow(stringResource(R.string.about_version), version)
                SettingRow(stringResource(R.string.about_licence), "AGPL-3.0", trailing = link) { uri.openUri(AGPL) }
                SettingRow(stringResource(R.string.about_source), SOURCE.removePrefix("https://"), trailing = link) { uri.openUri(SOURCE) }
            }
            SettingsGroup(stringResource(CoreR.string.syncthing)) {
                SettingRow(stringResource(R.string.about_version), SyncthingCore.version)
                SettingRow(stringResource(R.string.about_licence), "MPL-2.0", trailing = link) { uri.openUri(MPL) }
                SettingRow(stringResource(R.string.about_docs), DOCS.removePrefix("https://").trimEnd('/'), trailing = link) { uri.openUri(DOCS) }
            }
            SettingsGroup(stringResource(R.string.about_other)) {
                SettingRow(stringResource(R.string.about_licences), stringResource(R.string.about_licences_hint)) { libraries = true }
            }
        }
    }
    if (libraries) {
        AlertDialog(
            onDismissRequest = { libraries = false },
            title = { Text(stringResource(R.string.about_licences)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LIBRARIES.forEach { (name, licence) -> SettingRow(name.asString(), licence.asString()) }
                    SettingRow("Apache License 2.0", stringResource(R.string.about_full_text), trailing = link) { uri.openUri(APACHE) }
                }
            },
            confirmButton = { TextButton(onClick = { libraries = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
