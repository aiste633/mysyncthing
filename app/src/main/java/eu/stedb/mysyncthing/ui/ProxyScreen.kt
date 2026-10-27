package eu.stedb.mysyncthing.ui

import androidx.compose.ui.res.stringResource
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.joined
import eu.stedb.mysyncthing.core.text
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
        title = stringResource(R.string.proxy),
        onBack = back,
        actions = {
            TextButton(enabled = result != null && !saving, onClick = {
                saving = true
                scope.launch { if (save(result!!)) onBack() else saving = false }
            }) { Text(stringResource(R.string.action_save)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SettingsGroup(stringResource(R.string.proxy_connect_through)) {
                ChoiceList(
                    listOf(ProxyType.None to text(R.string.proxy_none), ProxyType.Socks5 to text(R.string.proxy_socks5), ProxyType.Http to text(R.string.proxy_http)),
                    type, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    describe = { if (it == ProxyType.Http) text(R.string.proxy_http_hint) else null },
                ) { type = it }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(shape = ButtonShape, onClick = {
                    val t = ProxySettings.TOR
                    type = t.type; host = t.host; port = t.port.toString(); user = ""; password = ""; only = t.only
                }) { Text(stringResource(R.string.proxy_use_tor)) }
            }
            if (type != ProxyType.None) {
                SettingsGroup(stringResource(R.string.proxy)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            host, { host = it }, label = { Text(stringResource(R.string.proxy_host)) }, singleLine = true, isError = hostError != null,
                            supportingText = { Text(hostError?.asString() ?: stringResource(R.string.proxy_host_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            port, { port = it }, label = { Text(stringResource(R.string.proxy_port)) }, singleLine = true, isError = portError != null,
                            supportingText = portError?.let { { Text(it.asString()) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            user, { user = it }, label = { Text(stringResource(R.string.proxy_user)) }, singleLine = true, isError = userError != null,
                            supportingText = userError?.let { { Text(it.asString()) } },
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            password, { password = it }, label = { Text(stringResource(R.string.password_optional)) }, singleLine = true,
                            isError = passwordError != null,
                            supportingText = (passwordError ?: httpPasswordNote(type, password))?.let { { Text(it.asString()) } },
                            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            trailingIcon = {
                                IconButton(onClick = { show = !show }) {
                                    Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (show) R.string.password_hide else R.string.password_show))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SwitchRow(
                        stringResource(R.string.proxy_only),
                        stringResource(R.string.proxy_only_hint),
                        only, onChange = { only = it },
                    )
                }
            }
            Text(
                proxyNote(type, running).asString(),
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

private fun httpPasswordNote(type: ProxyType, password: String): UiText? =
    text(R.string.proxy_http_password_note).takeIf { type == ProxyType.Http && password.isNotEmpty() }

private fun proxyNote(type: ProxyType, running: Boolean): UiText = listOfNotNull(
    text(R.string.proxy_note_lan).takeIf { type != ProxyType.None },
    text(R.string.proxy_note_tor).takeIf { type == ProxyType.Socks5 },
    text(R.string.proxy_note_restart).takeIf { running },
).joined(" ")

/** The Settings row's summary of a proxy. */
fun proxyText(p: ProxySettings): UiText = when (p.type) {
    ProxyType.None -> text(R.string.proxy_summary_none)
    else -> {
        val name = if (p.type == ProxyType.Socks5) "SOCKS5" else "HTTP"
        val host = if (':' in p.host) "[${p.host}]" else p.host
        text(if (p.only) R.string.proxy_summary_only else R.string.proxy_summary_fallback, "$name $host:${p.port}")
    }
}
