package eu.stedb.mysyncthing.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stedb.mysyncthing.core.ConfigExport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The export's state. A ViewModel, so rotation keeps what was typed; in memory
 * only, never saved instance state, as it holds the password.
 */
class ExportViewModel(app: Application) : AndroidViewModel(app) {
    var password by mutableStateOf("")
    var repeat by mutableStateOf("")

    private val _result = MutableStateFlow<ExportResult?>(null)
    val result = _result.asStateFlow()

    fun export(uri: Uri) {
        if (_result.value == ExportResult.Working) return
        _result.value = ExportResult.Working
        viewModelScope.launch {
            val problem = ConfigExport.export(getApplication(), uri, password)
            _result.value = if (problem == null) ExportResult.Saved(password.isNotEmpty()) else ExportResult.Failed(problem)
        }
    }

    fun again() {
        if (_result.value != ExportResult.Working) _result.value = null
    }
}

sealed interface ExportResult {
    data object Working : ExportResult
    data class Saved(val encrypted: Boolean) : ExportResult
    data class Failed(val message: String) : ExportResult
}

@Composable
fun ExportScreen(onBack: () -> Unit, vm: ExportViewModel = viewModel()) {
    val result by vm.result.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::export) }
    val working = result == ExportResult.Working
    BackHandler(enabled = working) {} // leaving would hide whether the file was written

    AppScreen("Export backup", onBack = if (working) null else onBack) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            when (val r = result) {
                null -> ExportForm(vm) { save.launch(ConfigExport.fileName()) }
                ExportResult.Working -> Column(
                    Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BusyIndicator()
                    Text("Exporting")
                }
                is ExportResult.Saved -> ExportNote(
                    "Exported",
                    "Import it on this or another phone in Settings > Import a backup." +
                        if (r.encrypted) " Keep the password: without it the backup can't be opened." else " Keep the file private: it holds this phone's private key.",
                )
                is ExportResult.Failed -> {
                    ExportNote("Export failed", r.message)
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        Button(shape = ButtonShape, onClick = vm::again) { Text("Try again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportForm(vm: ExportViewModel, onSave: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    val passwordError = exportPasswordError(vm.password)
    val repeatError = if (vm.password.isEmpty()) null else exportRepeatError(vm.password, vm.repeat)
    ExportNote(
        "Back up this phone's setup",
        "The backup holds the folders, devices, this phone's device ID with its private key, and the sync settings " +
            "including the proxy. Not the files in the folders, and not the web GUI login.",
    )
    SettingsGroup("Password (recommended)") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val transformation = if (show) VisualTransformation.None else PasswordVisualTransformation()
            val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
            OutlinedTextField(
                vm.password, { vm.password = it }, label = { Text("Password") }, singleLine = true,
                isError = passwordError != null,
                supportingText = { Text(passwordError ?: "At least $MIN_EXPORT_PASSWORD characters. Encrypts the backup with AES-256.") },
                visualTransformation = transformation, keyboardOptions = keyboard,
                trailingIcon = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide password" else "Show password")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (vm.password.isNotEmpty()) {
                OutlinedTextField(
                    vm.repeat, { vm.repeat = it }, label = { Text("Password again") }, singleLine = true,
                    isError = vm.repeat.isNotEmpty() && repeatError != null,
                    supportingText = repeatError?.takeIf { vm.repeat.isNotEmpty() }?.let { { Text(it) } },
                    visualTransformation = transformation, keyboardOptions = keyboard,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    if (vm.password.isEmpty()) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "Without a password, any app or person that can read the file can pose as this phone.",
                    color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
    Row(Modifier.padding(16.dp)) {
        Button(shape = ButtonShape, enabled = passwordError == null && repeatError == null, onClick = onSave) {
            Icon(Icons.Default.SaveAlt, null)
            Text("Choose where to save", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ExportNote(title: String, text: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
