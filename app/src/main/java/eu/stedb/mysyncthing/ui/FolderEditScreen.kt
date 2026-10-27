@file:OptIn(ExperimentalMaterial3Api::class)

package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderDevice
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Versioning
import java.util.UUID

/** A folder to create, possibly pre-filled from a pending offer. */
data class FolderDraft(
    val id: String,
    val label: String,
    val path: String = "",
    val devices: Set<String> = emptySet(),
    val type: String = "sendreceive",
    val versioning: Versioning = Versioning(),
    /** Add it paused and open the ignore patterns, so nothing syncs before they apply. */
    val ignoresFirst: Boolean = false,
    /** Encryption passwords for the untrusted devices it is shared with. */
    val passwords: Map<String, String> = emptyMap(),
)

/** The defaults' folder location, or Syncthing/ in shared storage while it is upstream's "~". */
fun pathBase(defaults: Folder): String = defaults.path.trimEnd('/').takeIf { it.startsWith("/") } ?: "$INTERNAL_STORAGE/Syncthing"

/** Draft for a new local folder, or for an offer when [offerId] is set, starting from the [defaults]. */
fun draftFor(s: Snapshot, offerId: String?, offerDevice: String?, defaults: Folder): FolderDraft {
    val base = FolderDraft(newFolderId(), "", type = defaults.type, versioning = defaults.versioning)
    if (offerId == null) return base
    val label = s.pendingFolders[offerId]?.offeredBy?.get(offerDevice)?.label?.ifEmpty { null } ?: offerId
    return base.copy(id = offerId, label = label, path = suggestedPath(label, pathBase(defaults), offerId), devices = setOfNotNull(offerDevice))
}

/**
 * Full-screen form for adding a folder or accepting a shared one. An offer
 * keeps its ID (it must match the other side) and gets a suggested path.
 */
@Composable
fun FolderEditScreen(
    s: Snapshot, initial: FolderDraft, offeredBy: String?, pathBase: String, onBack: () -> Unit, onSave: (FolderDraft) -> Unit,
) {
    var d by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(initial) }
    // Follow the label into the suggested path until the user picks a place.
    var pathTouched by rememberSaveable { mutableStateOf(offeredBy == null) }
    val pick = rememberFolderPicker {
        pathTouched = true
        d = d.copy(path = it, label = d.label.ifEmpty { it.substringAfterLast('/') })
    }
    val isOffer = offeredBy != null
    // The untrusted device whose password dialog is open.
    var encrypt by rememberSaveable { mutableStateOf<String?>(null) }
    s.devices.find { it.deviceID == encrypt }?.let { dev ->
        PasswordDialog(
            d.label.ifEmpty { d.id }, dev.name.ifEmpty { shortId(dev.deviceID) }, "", shared = false,
            onDismiss = { encrypt = null }, onUnshare = {},
        ) {
            d = d.copy(devices = d.devices + dev.deviceID, passwords = d.passwords + (dev.deviceID to it))
            encrypt = null
        }
    }
    val who = offeredBy?.let { id -> s.devices.find { it.deviceID == id }?.name?.ifEmpty { null } ?: shortId(id) }
    val pathError = folderPathError(d.path, s.folders.map { it.path })
    // An offer's ID is the other device's; only a typed one must also work in REST paths.
    val idError = if (isOffer) "Already added".takeIf { s.folders.any { it.id == d.id } } else folderIdError(d.id, s.folders.map { it.id })
    val valid = pathError == null && idError == null

    val back = rememberDiscardGuard(d != initial, onBack)

    AppScreen(
        title = if (isOffer) "Add \"${initial.label}\"" else "Add folder",
        onBack = back,
        actions = { TextButton(onClick = { onSave(d) }, enabled = valid) { Text("Save") } },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (who != null) {
                Text(
                    "Offered by $who", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
            SettingsGroup("Folder") {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        d.label,
                        { d = d.copy(label = it, path = if (pathTouched) d.path else suggestedPath(it, pathBase, d.id)) },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        d.path, { pathTouched = true; d = d.copy(path = it) },
                        label = { Text("Folder on this phone") }, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        // An empty path on a new form is not yet a mistake: no red, but say it's needed.
                        isError = d.path.isNotBlank() && pathError != null,
                        supportingText = {
                            Text(
                                when {
                                    d.path.isBlank() -> "Required. Full path, or choose one"
                                    pathError != null -> pathError
                                    isOffer && !pathTouched -> "Suggested. It is created if missing."
                                    else -> "Full path, or choose one"
                                },
                            )
                        },
                    )
                    TextButton(onClick = pick) { Text("Choose folder") }
                    if (!isOffer) {
                        OutlinedTextField(
                            d.id, { d = d.copy(id = it) }, label = { Text("Folder ID") }, singleLine = true, isError = idError != null,
                            supportingText = { Text(idError ?: "Must be the same on every device sharing this folder") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            SettingsGroup("Type") {
                ChoiceList(FOLDER_TYPES, d.type, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), ::folderTypeHint) { d = d.copy(type = it) }
            }
            SettingsGroup("Share with") {
                if (s.devices.isEmpty()) SettingRow("No devices added yet", "Add one on the Devices tab")
                s.devices.forEach { dev ->
                    val on = dev.deviceID in d.devices
                    val note = if (dev.deviceID == offeredBy) "Offered this folder"
                    else encryptionText(dev, d.passwords[dev.deviceID]?.let { FolderDevice(dev.deviceID, encryptionPassword = it) }.takeIf { on })
                    CheckRow(dev.name.ifEmpty { shortId(dev.deviceID) }, note, on) { share ->
                        when {
                            !share -> d = d.copy(devices = d.devices - dev.deviceID, passwords = d.passwords - dev.deviceID)
                            dev.untrusted -> encrypt = dev.deviceID
                            else -> d = d.copy(devices = d.devices + dev.deviceID)
                        }
                    }
                }
            }
            SettingsGroup("File versioning") {
                VersioningEditor(d.versioning) { d = d.copy(versioning = it) }
            }
            SettingsGroup("Ignore patterns") {
                CheckRow("Set ignore patterns first", "The folder stays paused until you save them", d.ignoresFirst) {
                    d = d.copy(ignoresFirst = it)
                }
            }
        }
    }
}

/** Method picker plus its one number; shared by the add and settings screens. */
@Composable
fun VersioningEditor(v: Versioning, onChange: (Versioning) -> Unit) {
    var choose by rememberSaveable { mutableStateOf(false) }
    var editParam by rememberSaveable { mutableStateOf(false) }
    SettingRow("Method", versioningSummary(v.type, v.params).substringBefore(" · ")) { choose = true }
    val param = versioningParam(v.type)
    if (param != null) {
        val n = versioningShown(v.type, v.params[param.key])
        SettingRow(param.label, if (param.unit == "days" && n == "0") "Forever" else "$n ${param.unit}") { editParam = true }
    }
    versioningHint(v.type)?.let {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (choose) {
        ChoiceDialog("File versioning", VERSIONING_TYPES, v.type, ::versioningHint, onDismiss = { choose = false }) { type ->
            choose = false
            val p = versioningParam(type)
            onChange(Versioning(type, if (p == null) emptyMap() else mapOf(p.key to versioningStored(type, p.default))))
        }
    }
    if (editParam && param != null) {
        TextDialog(
            param.label, versioningShown(v.type, v.params[param.key]), param.unit.replaceFirstChar { it.uppercase() },
            error = { wholeNumberError(it, param.min) },
            onDismiss = { editParam = false },
        ) {
            editParam = false
            onChange(v.copy(params = v.params + (param.key to versioningStored(v.type, it))))
        }
    }
}

/** Upstream-style random ID: xxxxx-xxxxx. */
private fun newFolderId(): String = UUID.randomUUID().toString().replace("-", "").take(10).chunked(5).joinToString("-")

internal val draftSaver = androidx.compose.runtime.saveable.Saver<FolderDraft, List<Any>>(
    save = {
        listOf(
            it.id, it.label, it.path, ArrayList(it.devices), it.type, it.versioning.type, HashMap(it.versioning.params), it.ignoresFirst,
            HashMap(it.passwords),
        )
    },
    restore = {
        @Suppress("UNCHECKED_CAST")
        FolderDraft(
            it[0] as String, it[1] as String, it[2] as String, (it[3] as List<String>).toSet(), it[4] as String,
            Versioning(it[5] as String, it[6] as Map<String, String>), it[7] as Boolean, it[8] as Map<String, String>,
        )
    },
)
