package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.DiskChange
import eu.stedb.mysyncthing.core.FileVersion
import eu.stedb.mysyncthing.core.Snapshot
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** One file change from the core's in-memory event buffer; [fromThisPhone] when this phone's scan found it. */
data class RecentChange(val id: Long, val time: String, val fromThisPhone: Boolean, val change: DiskChange)

/**
 * The latest file changes in all folders, as far back as the core remembers
 * since it started. Nothing is stored by the app.
 */
@Composable
fun RecentChangesScreen(s: Snapshot, vm: MainViewModel, onBack: () -> Unit) {
    var changes by remember { mutableStateOf<List<RecentChange>?>(null) }
    // Folder states change whenever files do, so this follows new changes without a second event poll.
    LaunchedEffect(s.folderStatus) { vm.recentChanges(RECENT_LIMIT)?.let { changes = it } }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = changes.orEmpty().filter { folder == null || it.change.folder == folder }

    AppScreen(title = "Recent changes", onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
            item("chips") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(folder == null, { folder = null }, { Text("All folders") }, shape = ButtonShape)
                    s.folders.forEach { f ->
                        FilterChip(folder == f.id, { folder = f.id }, { Text(f.label.ifEmpty { f.id }) }, shape = ButtonShape)
                    }
                }
            }
            when {
                changes == null -> item("loading") { Hint("Loading") }
                shown.isEmpty() -> item("none") { Hint("No changes since Syncthing started.") }
            }
            shown.groupBy { dayText(it.time) ?: "" }.forEach { (day, list) ->
                item("day-$day") { GroupTitle(day) }
                item("list-$day") {
                    ListCard {
                        list.forEach { ChangeRow(it, s) }
                    }
                }
            }
            if (shown.isNotEmpty()) item("note") { Hint("Syncthing keeps its latest ${RECENT_LIMIT} changes in memory until it restarts.") }
        }
    }
}

private const val RECENT_LIMIT = 500

@Composable
private fun ChangeRow(r: RecentChange, s: Snapshot) {
    val c = r.change
    val deleted = c.action == "deleted"
    val who = when {
        r.fromThisPhone || c.modifiedBy == shortId(s.myId) -> "on this phone"
        else -> "by " + (s.devices.find { shortId(it.deviceID) == c.modifiedBy }?.name?.ifEmpty { null } ?: c.modifiedBy)
    }
    val what = (if (deleted) "deleted " else "changed ") + who
    val folder = s.folders.find { it.id == c.folder }?.label?.ifEmpty { null } ?: c.label.ifEmpty { c.folder }
    val dir = c.path.substringBeforeLast('/', "")
    SettingRow(
        c.path.substringAfterLast('/'),
        listOfNotNull(folder + if (dir.isEmpty()) "" else "/$dir", what, exactTimeText(r.time)).joinToString(" · "),
        icon = when {
            c.type == "dir" -> Icons.Default.Folder
            deleted -> Icons.Default.Delete
            else -> Icons.Default.Edit
        },
        danger = deleted,
    )
}

private enum class Range(val label: String, val days: Long?) { Week("Last 7 days", 7), Month("Last 30 days", 30), All("All", null) }

/**
 * A folder's archived versions: searchable, filtered by when they were
 * archived. Restoring keeps the current file as a version, so nothing is lost.
 */
@Composable
fun VersionsScreen(s: Snapshot, folder: String, vm: MainViewModel, onBack: () -> Unit) {
    val f = s.folders.find { it.id == folder }
    var versions by remember { mutableStateOf<Map<String, List<FileVersion>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(folder, reload) { versions = vm.versions(folder) }
    var range by rememberSaveable { mutableStateOf(Range.Week) }
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val since = range.days?.let { Instant.now().minus(it, ChronoUnit.DAYS) }
    // Per file, its versions newest first, kept when at least one falls in the range.
    val files = versions.orEmpty().mapNotNull { (path, list) ->
        val sorted = list.sortedByDescending { instant(it.versionTime) }
        val inRange = sorted.filter { since == null || (instant(it.versionTime) ?: Instant.MIN) >= since }
        if (inRange.isEmpty() || (query.isNotBlank() && query.trim().lowercase() !in path.lowercase())) null else Triple(path, sorted, inRange)
    }.sortedByDescending { instant(it.third.first().versionTime) }

    AppScreen(
        title = "Restore versions",
        onBack = onBack,
        actions = { TextButton(enabled = files.isNotEmpty(), onClick = { confirmAll = true }) { Text("Restore all") } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
            item("filters") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f?.label?.ifEmpty { null } ?: folder, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Range.entries.forEach { r -> FilterChip(range == r, { range = r }, { Text(r.label) }, shape = ButtonShape) }
                    }
                    OutlinedTextField(
                        query, { query = it }, singleLine = true, label = { Text("Search") },
                        leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            when {
                versions == null -> item("loading") { Hint("Loading") }
                files.isEmpty() -> item("none") { Hint(if (versions!!.isEmpty()) "No old versions kept in this folder." else "No versions match.") }
            }
            items(files, key = { it.first }) { (path, all, inRange) ->
                ListCard {
                    SettingRow(
                        path.substringAfterLast('/'),
                        listOfNotNull(
                            path.substringBeforeLast('/', "").ifEmpty { null },
                            count(all.size, "version"),
                            whenText(inRange.first().versionTime)?.let { "latest $it" },
                        ).joinToString(" · "),
                        icon = Icons.Default.History,
                    ) { open = if (open == path) null else path }
                    if (open == path) {
                        VersionPicker(all, inRange.first(), f?.type) { v ->
                            scope.launch { if (vm.restoreVersions(folder, mapOf(path to v.versionTime))) { open = null; reload++ } }
                        }
                    }
                }
            }
        }
    }
    if (confirmAll) {
        ConfirmDialog(
            "Restore ${count(files.size, "file")}?",
            "Each goes back to its newest version" + (if (range.days == null) "" else " from the ${range.label.lowercase()}") +
                ". The current files are kept as versions. " + restoreNote(f?.type, plural = true),
            "Restore", onDismiss = { confirmAll = false },
        ) {
            confirmAll = false
            val pick = files.associate { (path, _, inRange) -> path to inRange.first().versionTime }
            scope.launch { if (vm.restoreVersions(folder, pick)) reload++ }
        }
    }
}

@Composable
private fun VersionPicker(versions: List<FileVersion>, initial: FileVersion, folderType: String?, onRestore: (FileVersion) -> Unit) {
    var picked by rememberSaveable { mutableStateOf(initial.versionTime) }
    Column(Modifier.padding(start = 40.dp, end = 16.dp, bottom = 12.dp)) {
        versions.forEach { v ->
            Row(
                Modifier.fillMaxWidth().selectable(picked == v.versionTime, role = Role.RadioButton) { picked = v.versionTime },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(picked == v.versionTime, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text("${whenText(v.versionTime) ?: v.versionTime} · ${bytes(v.size)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        FilledTonalButton(shape = ButtonShape, onClick = { versions.find { it.versionTime == picked }?.let(onRestore) }, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Restore")
        }
        Text(
            "The current file is kept as a version. " + restoreNote(folderType, plural = false),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun instant(rfc3339: String): Instant? = runCatching { OffsetDateTime.parse(rfc3339).toInstant() }.getOrNull()

@Composable
private fun Hint(text: String) = Text(
    text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
)

/** A restore dates the files now (so it always syncs); says so, and where they go. */
private fun restoreNote(folderType: String?, plural: Boolean): String {
    val what = if (plural) "Restored files are" else "The restored file is"
    return if (folderType == "receiveonly") "$what dated now and stay${if (plural) "" else "s"} a local change: this folder is receive only."
    else "$what dated now and sync${if (plural) "" else "s"} to every device that shares this folder."
}
