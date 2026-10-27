package eu.stedb.mysyncthing.ui

import androidx.compose.ui.res.stringResource
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.Device
import eu.stedb.mysyncthing.core.FolderDevice

/**
 * The share-row text about encryption, or null for a plain share. A password
 * on the share, not the device's trust, decides: a share made while the
 * device was untrusted stays encrypted after it is trusted again.
 */
fun encryptionText(device: Device, entry: FolderDevice?): UiText? = when {
    entry != null && entry.encryptionPassword.isNotEmpty() -> text(R.string.encryption_set)
    !device.untrusted -> null
    entry == null -> text(R.string.encryption_needed)
    else -> text(R.string.encryption_missing)
}

/** Whether tapping the share opens the password dialog instead of toggling it. */
fun sharesEncrypted(device: Device, entry: FolderDevice?): Boolean =
    device.untrusted || entry?.encryptionPassword?.isNotEmpty() == true

/**
 * Password for sharing [folder] with the untrusted [device], which then
 * stores it encrypted. [shared] adds Stop sharing.
 */
@Composable
fun PasswordDialog(
    folder: String,
    device: String,
    initial: String,
    shared: Boolean,
    onDismiss: () -> Unit,
    onUnshare: () -> Unit,
    onSave: (String) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf(initial) }
    var show by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.encryption_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.encryption_text, device, folder))
                OutlinedTextField(
                    password, { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                    supportingText = requiredError(password)?.let { { Text(it.asString()) } },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (show) R.string.password_hide else R.string.password_show))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.encryption_keep_safe, device),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (shared) TextButton(onClick = onUnshare) { Text(stringResource(R.string.action_stop_sharing), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(password) }, enabled = password.isNotEmpty()) { Text(stringResource(if (shared) R.string.action_save else R.string.action_share)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
