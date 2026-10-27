package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.core.CoreState
import eu.stedb.mysyncthing.core.ProxySettings
import eu.stedb.mysyncthing.core.ProxyType
import kotlinx.coroutines.launch

/** The proxy the core connects through (go/bridge SetProxy). Saving restarts a running core. */
@Composable
fun ProxyScreen(vm: MainViewModel, core: CoreState, onBack: () -> Unit) {
    val stored by vm.proxy.collectAsStateWithLifecycle()
    // Loaded from storage first, so the form starts from what is saved.
    stored?.let { ProxyForm(it, core is CoreState.Running, vm::setProxy, onBack) }
}

@Composable
private fun ProxyForm(initial: ProxySettings, running: Boolean, save: suspend (ProxySettings) -> Boolean, onBack: () -> Unit) {
    var type by rememberSaveable { mutableStateOf(initial.type) }
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(if (initial.port == 0) "" else initial.port.toString()) }
    var user by rememberSaveable { mutableStateOf(initial.user) }
    // Not saveable: the password stays out of the saved instance state.
    var password by remember { mutableStateOf(initial.password) }
    var only by rememberSaveable { mutableStateOf(initial.only) }
    var show by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val result = proxyForm(type, host, port, user, password, only)
    val back = rememberDiscardGuard(result != initial, onBack)
    val hostError = proxyHostError(host.trim()).takeIf { host.isNotBlank() }
    val portError = proxyPortError(port).takeIf { port.isNotBlank() }
    val userError = proxyUserError(user, password).takeIf { user.isNotEmpty() || password.isNotEmpty() }
    val passwordError = proxyCredentialError(password)

    AppScreen(
        title = "Proxy",
        onBack = back,
        actions = {
            TextButton(enabled = result != null && !saving, onClick = {
                saving = true
                scope.launch { if (save(result!!)) onBack() else saving = false }
            }) { Text("Save") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SettingsGroup("Connect through") {
                ChoiceList(
                    listOf(ProxyType.None to "No proxy", ProxyType.Socks5 to "SOCKS5 proxy", ProxyType.Http to "HTTP proxy"),
                    type, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    describe = { if (it == ProxyType.Http) "Uses CONNECT, as for HTTPS" else null },
                ) { type = it }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(shape = ButtonShape, onClick = {
                    val t = ProxySettings.TOR
                    type = t.type; host = t.host; port = t.port.toString(); user = ""; password = ""; only = t.only
                }) { Text("Use Tor (Orbot)") }
            }
            if (type != ProxyType.None) {
                SettingsGroup("Proxy") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            host, { host = it }, label = { Text("Host") }, singleLine = true, isError = hostError != null,
                            supportingText = { Text(hostError ?: "IP address or name, for example 127.0.0.1") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            port, { port = it }, label = { Text("Port") }, singleLine = true, isError = portError != null,
                            supportingText = portError?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            user, { user = it }, label = { Text("User name (optional)") }, singleLine = true, isError = userError != null,
                            supportingText = userError?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            password, { password = it }, label = { Text("Password (optional)") }, singleLine = true,
                            isError = passwordError != null,
                            supportingText = (passwordError ?: httpPasswordNote(type, password))?.let { { Text(it) } },
                            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            trailingIcon = {
                                IconButton(onClick = { show = !show }) {
                                    Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide password" else "Show password")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SwitchRow(
                        "Only through the proxy",
                        "If the proxy can't be reached, Syncthing waits instead of connecting directly. " +
                            "QUIC and router port opening are off, as they would go around the proxy.",
                        only, onChange = { only = it },
                    )
                }
            }
            Text(
                proxyNote(type, running),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            )
        }
    }
}

/** The settings the form holds, or null while a field is not valid. "No proxy" keeps no other fields. */
fun proxyForm(type: ProxyType, host: String, port: String, user: String, password: String, only: Boolean): ProxySettings? {
    if (type == ProxyType.None) return ProxySettings()
    val p = ProxySettings(type, host.trim(), wholeNumber(port) ?: 0, user, password, only)
    return p.takeIf { proxyPortError(port) == null && proxyError(type.scheme, p.host, p.port, user, password) == null }
}

private fun httpPasswordNote(type: ProxyType, password: String): String? =
    "An HTTP proxy gets the user name and password unencrypted".takeIf { type == ProxyType.Http && password.isNotEmpty() }

private fun proxyNote(type: ProxyType, running: Boolean): String = listOfNotNull(
    "Devices on the local network are reached directly, without the proxy.".takeIf { type != ProxyType.None },
    "For Tor, Orbot or another Tor app must run on this phone.".takeIf { type == ProxyType.Socks5 },
    "Saving restarts Syncthing.".takeIf { running },
).joinToString(" ")

/** The Settings row's summary of a proxy. */
fun proxyText(p: ProxySettings): String = when (p.type) {
    ProxyType.None -> "None: direct connections"
    else -> {
        val name = if (p.type == ProxyType.Socks5) "SOCKS5" else "HTTP"
        val host = if (':' in p.host) "[${p.host}]" else p.host
        "$name $host:${p.port}" + if (p.only) ", nothing else" else ", direct if it fails"
    }
}
