@file:OptIn(ExperimentalMaterial3Api::class)

package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
)

/** Where accepted folders go unless the user picks another place. */
fun suggestedPath(label: String): String =
    "$INTERNAL_STORAGE/Syncthing/" + label.trim().replace('/', '_').ifEmpty { "Folder" }

/** Draft for a new local folder, or for an offer when [offerId] is set. */
fun draftFor(s: Snapshot, offerId: String?, offerDevice: String?): FolderDraft {
    if (offerId == null) return FolderDraft(newFolderId(), "")
    val label = s.pendingFolders[offerId]?.offeredBy?.get(offerDevice)?.label?.ifEmpty { null } ?: offerId
    return FolderDraft(offerId, label, suggestedPath(label), setOfNotNull(offerDevice))
}

/**
 * Full-screen form for adding a folder or accepting a shared one. An offer
 * keeps its ID (it must match the other side) and gets a suggested path.
 */
@Composable
fun FolderEditScreen(s: Snapshot, initial: FolderDraft, offeredBy: String?, onBack: () -> Unit, onSave: (FolderDraft) -> Unit) {
    var d by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(initial) }
    // Follow the label into the suggested path until the user picks a place.
    var pathTouched by rememberSaveable { mutableStateOf(offeredBy == null) }
    val pick = rememberFolderPicker {
        pathTouched = true
        d = d.copy(path = it, label = d.label.ifEmpty { it.substringAfterLast('/') })
    }
    val isOffer = offeredBy != null
    val who = offeredBy?.let { id -> s.devices.find { it.deviceID == id }?.name?.ifEmpty { null } ?: shortId(id) }
    val valid = d.id.isNotBlank() && d.path.startsWith("/") && s.folders.none { it.id == d.id.trim() }

    AppScreen(
        title = if (isOffer) "Add \"${initial.label}\"" else "Add folder",
        onBack = onBack,
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
                        { d = d.copy(label = it, path = if (pathTouched) d.path else suggestedPath(it)) },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        d.path, { pathTouched = true; d = d.copy(path = it) },
                        label = { Text("Folder on this phone") }, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        supportingText = { Text(if (isOffer && !pathTouched) "Suggested. It is created if missing." else "Full path, or choose one") },
                    )
                    TextButton(onClick = pick) { Text("Choose folder") }
                    if (!isOffer) {
                        OutlinedTextField(
                            d.id, { d = d.copy(id = it) }, label = { Text("Folder ID") }, singleLine = true,
                            supportingText = { Text("Must be the same on every device sharing this folder") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            SettingsGroup("Type") {
                Column(Modifier.padding(16.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        FOLDER_TYPES.forEachIndexed { i, (value, label) ->
                            SegmentedButton(
                                selected = d.type == value, onClick = { d = d.copy(type = value) },
                                modifier = Modifier.fillMaxHeight(),
                                shape = SegmentedButtonDefaults.itemShape(i, FOLDER_TYPES.size, MaterialTheme.shapes.extraSmall),
                                icon = {}, // the check mark would not leave room for the labels on a phone
                                colors = SegmentedButtonDefaults.colors(inactiveContainerColor = Color.Transparent),
                            ) {
                                // Two lines: with a larger display size "Send & receive" no longer fits in one.
                                Text(label, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    folderTypeHint(d.type)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            SettingsGroup("Share with") {
                if (s.devices.isEmpty()) SettingRow("No devices added yet", "Add one on the Devices tab")
                s.devices.forEach { dev ->
                    val on = dev.deviceID in d.devices
                    ListItem(
                        modifier = Modifier.clickable { d = d.copy(devices = if (on) d.devices - dev.deviceID else d.devices + dev.deviceID) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Checkbox(on, onCheckedChange = null) },
                        headlineContent = { Text(dev.name.ifEmpty { shortId(dev.deviceID) }) },
                        supportingContent = if (dev.deviceID == offeredBy) ({ Text("Offered this folder") }) else null,
                    )
                }
            }
            SettingsGroup("File versioning") {
                VersioningEditor(d.versioning) { d = d.copy(versioning = it) }
            }
            SettingsGroup("Ignore patterns") {
                SettingRow(
                    "Set ignore patterns first", "The folder stays paused until you save them",
                    trailing = { Checkbox(d.ignoresFirst, onCheckedChange = null) },
                ) { d = d.copy(ignoresFirst = !d.ignoresFirst) }
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
            valid = { it.toIntOrNull()?.let { n -> n >= 0 } == true },
            onDismiss = { editParam = false },
        ) {
            editParam = false
            onChange(v.copy(params = v.params + (param.key to versioningStored(v.type, it.trim()))))
        }
    }
}

/** Upstream-style random ID: xxxxx-xxxxx. */
private fun newFolderId(): String = UUID.randomUUID().toString().replace("-", "").take(10).chunked(5).joinToString("-")

private val draftSaver = androidx.compose.runtime.saveable.Saver<FolderDraft, List<Any>>(
    save = {
        listOf(it.id, it.label, it.path, ArrayList(it.devices), it.type, it.versioning.type, HashMap(it.versioning.params), it.ignoresFirst)
    },
    restore = {
        @Suppress("UNCHECKED_CAST")
        FolderDraft(
            it[0] as String, it[1] as String, it[2] as String, (it[3] as List<String>).toSet(), it[4] as String,
            Versioning(it[5] as String, it[6] as Map<String, String>), it[7] as Boolean,
        )
    },
)
