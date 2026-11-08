@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.FolderDevice
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.Versioning
import eu.stedb.mysyncthing.core.text
import kotlinx.coroutines.withContext
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
fun pathBase(defaults: Folder): String = defaults.path.trimEnd('/').takeIf { it.startsWith("/") } ?: "$internalStorage/Syncthing"

/**
 * Draft for a new local folder, named [newName] and placed at its suggested
 * path, or for an offer when [offerId] is set; both start from the [defaults].
 */
fun draftFor(s: Snapshot, offerId: String?, offerDevice: String?, defaults: Folder, newName: String = ""): FolderDraft {
    val base = FolderDraft(newFolderId(), "", type = defaults.type, versioning = defaults.versioning)
    if (offerId == null) return base.copy(label = newName, path = if (newName.isEmpty()) "" else suggestedPath(newName, pathBase(defaults), base.id))
    val label = s.pendingFolders[offerId]?.offeredBy?.get(offerDevice)?.label?.ifEmpty { null } ?: offerId
    return base.copy(id = offerId, label = label, path = suggestedPath(label, pathBase(defaults), offerId), devices = setOfNotNull(offerDevice))
}

/**
 * The name a new folder starts with: [first] ("New folder"), else "New folder 2", 3 and on, the
 * first whose suggested place in [base] is neither a directory ([exists]) nor in, around or at a
 * synced folder, so saving it as is starts an empty folder. [first] when all 999 are taken.
 */
fun newFolderName(first: String, base: String, folders: List<Folder>, exists: (String) -> Boolean): String {
    val synced = folders.map { folderPath(it.path) }
    return (1..999).asSequence().map { if (it == 1) first else "$first $it" }
        .firstOrNull { n -> val p = folderPath(suggestedPath(n, base)); synced.none { overlaps(p, it) } && !exists(p) } ?: first
}

/**
 * The quick picks shown: the [suggestion] (Syncthing/<name>) first, unless it is the [chosen]
 * path, then the usual folders ([picks]), each once.
 */
fun quickPickChips(suggestion: String?, picks: List<String>, chosen: String): List<String> =
    (listOfNotNull(suggestion?.takeUnless { folderPath(it).equals(chosen, ignoreCase = true) }) + picks).distinct()

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
    var pathTouched by rememberSaveable { mutableStateOf(false) }
    // The name field's text and selection. A new folder's name starts as "New folder", selected,
    // and the first tap in it selects it all again, so typing replaces it.
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial.label, if (offeredBy == null) TextRange(0, initial.label.length) else TextRange(initial.label.length)))
    }
    var nameTapped by rememberSaveable { mutableStateOf(offeredBy != null) }
    // Until the user types a name, a place picked by quick pick or Browse names the folder
    // ("New folder" becomes "Camera"). An offer keeps the name the other device gave it.
    var nameTyped by rememberSaveable { mutableStateOf(offeredBy != null) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    val disk = rememberDisk()
    // Read off the main thread: storages can be slow.
    val roots by produceState(emptyList<StorageRoot>(), disk) { value = withContext(disk.reads) { disk.roots() } }
    val choose = { path: String ->
        pathTouched = true
        if (!nameTyped || d.label.isEmpty()) name = TextFieldValue(path.substringAfterLast('/')).let { it.copy(selection = TextRange(it.text.length)) }
        d = d.copy(path = path, label = name.text)
    }
    val picks by produceState(emptyList<String>(), disk) { value = withContext(disk.reads) { quickPicks(disk) } }
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
    val pathError = pickProblem(d.path, s.folders, pathBase)
    // An offer's ID is the other device's; only a typed one must also work in REST paths.
    val idError = if (isOffer) text(R.string.folder_already_added).takeIf { s.folders.any { it.id == d.id } } else folderIdError(d.id, s.folders.map { it.id })
    val valid = pathError == null && idError == null

    val back = rememberDiscardGuard(d != initial, onBack)
    if (browsing) {
        FolderBrowser(d.path, pathBase, s.folders, onBack = { browsing = false }) {
            choose(it)
            browsing = false
        }
        return
    }

    AppScreen(
        title = if (isOffer) stringResource(R.string.folder_add_offer, initial.label) else stringResource(R.string.folders_add),
        onBack = back,
        actions = { TextButton(onClick = { onSave(d) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (who != null) {
                Text(
                    stringResource(R.string.folder_offered_by, who), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
            SettingsGroup(stringResource(R.string.folder)) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        name,
                        { v ->
                            if (v.text != name.text) nameTyped = true
                            // The tap that puts the cursor in changes only the selection.
                            name = if (!nameTapped && v.text == name.text) v.copy(selection = TextRange(0, v.text.length)) else v
                            nameTapped = true
                            if (v.text != d.label) d = d.copy(label = v.text, path = if (pathTouched) d.path else suggestedPath(v.text, pathBase, d.id))
                        },
                        label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("folderName"),
                    )
                    // An empty path on a new form is not yet a mistake: no red, but say it's needed.
                    val error = d.path.isNotBlank() && pathError != null
                    PathRow(d.path, roots, error, Modifier.padding(top = 12.dp)) { browsing = true }
                    Text(
                        when {
                            d.path.isBlank() -> stringResource(R.string.folder_path_needed)
                            pathError != null -> pathError.asString()
                            !pathTouched -> stringResource(R.string.folder_path_suggested)
                            else -> stringResource(R.string.folder_path_created)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                    // Syncthing/<name> first, while there is a name to put there.
                    val suggestion = if (isOffer || d.label.isNotBlank()) suggestedPath(d.label, pathBase, d.id) else null
                    QuickPicks(suggestion, picks, folderPath(d.path), s.folders, roots, pathBase, Modifier.padding(top = 12.dp)) { p ->
                        if (p == suggestion) {
                            pathTouched = false
                            d = d.copy(path = p)
                        } else {
                            choose(p)
                        }
                    }
                    OutlinedButton(onClick = { browsing = true }, shape = ButtonShape, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).testTag("browse")) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.folder_browse), Modifier.padding(start = 8.dp))
                    }
                    if (!isOffer) {
                        OutlinedTextField(
                            d.id, { d = d.copy(id = it) }, label = { Text(stringResource(R.string.folder_id)) }, singleLine = true, isError = idError != null,
                            supportingText = { Text(idError?.asString() ?: stringResource(R.string.folder_id_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            SettingsGroup(stringResource(R.string.type)) {
                ChoiceList(FOLDER_TYPES, d.type, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), ::folderTypeHint) { d = d.copy(type = it) }
            }
            SettingsGroup(stringResource(R.string.share_with)) {
                if (s.devices.isEmpty()) SettingRow(stringResource(R.string.share_no_devices), stringResource(R.string.share_no_devices_hint))
                s.devices.forEach { dev ->
                    val on = dev.deviceID in d.devices
                    val note = if (dev.deviceID == offeredBy) stringResource(R.string.share_offered)
                    else encryptionText(dev, d.passwords[dev.deviceID]?.let { FolderDevice(dev.deviceID, encryptionPassword = it) }.takeIf { on })?.asString()
                    CheckRow(dev.name.ifEmpty { shortId(dev.deviceID) }, note, on) { share ->
                        when {
                            !share -> d = d.copy(devices = d.devices - dev.deviceID, passwords = d.passwords - dev.deviceID)
                            dev.untrusted -> encrypt = dev.deviceID
                            else -> d = d.copy(devices = d.devices + dev.deviceID)
                        }
                    }
                }
            }
            SettingsGroup(stringResource(R.string.file_versioning)) {
                VersioningEditor(d.versioning) { d = d.copy(versioning = it) }
            }
            SettingsGroup(stringResource(R.string.ignore_patterns)) {
                CheckRow(stringResource(R.string.folder_ignores_first), stringResource(R.string.folder_ignores_first_hint), d.ignoresFirst) {
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
    SettingRow(stringResource(R.string.versioning_method), versioningLabel(v.type).asString()) { choose = true }
    val param = versioningParam(v.type)
    if (param != null) {
        SettingRow(stringResource(param.label), versioningAmount(param, versioningShown(v.type, v.params[param.key])).asString()) { editParam = true }
    }
    versioningHint(v.type)?.let {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (choose) {
        ChoiceDialog(stringResource(R.string.file_versioning), VERSIONING_TYPES, v.type, ::versioningHint, onDismiss = { choose = false }) { type ->
            choose = false
            val p = versioningParam(type)
            onChange(Versioning(type, if (p == null) emptyMap() else mapOf(p.key to versioningStored(type, p.default))))
        }
    }
    if (editParam && param != null) {
        TextDialog(
            stringResource(param.label), versioningShown(v.type, v.params[param.key]),
            stringResource(if (param.days) R.string.versioning_unit_days else R.string.versioning_unit_versions),
            supporting = if (param.days) stringResource(R.string.versioning_days_hint) else null,
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

/** The chosen folder: its storage and the path in it. The whole row opens the browser. */
@Composable
private fun PathRow(path: String, roots: List<StorageRoot>, error: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (top, rest) = pathParts(path, roots)
    val outline = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .border(1.dp, outline, MaterialTheme.shapes.extraSmall)
            .clickable(onClickLabel = stringResource(R.string.folder_browse), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("folderPath"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(stringResource(R.string.folder_path), style = MaterialTheme.typography.bodySmall, color = if (error) outline else MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                path.isBlank() -> Text(stringResource(R.string.folder_path_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                rest.isEmpty() -> Text(top?.asString() ?: path)
                else -> {
                    Text(rest)
                    top?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/**
 * One-tap places: the suggested Syncthing/<name> and the usual folders that exist.
 * One already synced, or around or inside a synced folder, can't be chosen.
 */
@Composable
private fun QuickPicks(
    suggestion: String?, picks: List<String>, chosen: String, folders: List<Folder>, roots: List<StorageRoot>, base: String,
    modifier: Modifier = Modifier, onPick: (String) -> Unit,
) {
    // The suggestion by where it goes in its storage ("Syncthing/Holiday 2026"), the usual folders by name.
    // The suggestion is left out while it is the chosen path, which the path row already shows.
    val shown = quickPickChips(suggestion, picks, chosen).map { it to if (it == suggestion) pathParts(it, roots).second else it.substringAfterLast('/') }
    if (shown.isEmpty()) return
    Column(modifier) {
        Text(stringResource(R.string.folder_quick_picks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("quickPicks")) {
            shown.forEach { (p, name) ->
                val synced = folders.any { folderPath(it.path) == p }
                FilterChip(
                    selected = chosen == p, onClick = { onPick(p) }, enabled = chosen == p || pickProblem(p, folders, base) == null,
                    label = { Text(if (synced) stringResource(R.string.folder_pick_synced, name) else name) },
                    shape = ButtonShape,
                )
            }
        }
    }
}
