package eu.stedb.mysyncthing.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.whenText
import eu.stedb.mysyncthing.core.DiskChange
import eu.stedb.mysyncthing.core.FileVersion
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.parseConflictCopy
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import eu.stedb.mysyncthing.core.R as CoreR

/** One file change from the core's in-memory event buffer; [fromThisPhone] when this phone's scan found it. */
data class RecentChange(val id: Long, val time: String, val fromThisPhone: Boolean, val change: DiskChange)

/**
 * The latest file changes in one folder, as far back as the core remembers
 * since it started. Nothing is stored by the app. Above them, while the folder
 * has any, the conflict copies on this phone ([Snapshot.conflicts]).
 */
@Composable
fun RecentChangesScreen(s: Snapshot, folder: String, vm: MainViewModel, onBack: () -> Unit) {
    var changes by remember { mutableStateOf<List<RecentChange>?>(null) }
    // Folder states change whenever files do, so this follows new changes without a second event poll.
    LaunchedEffect(s.folderStatus) { vm.recentChanges(RECENT_LIMIT)?.let { all -> changes = all.filter { it.change.folder == folder } } }
    val label = s.folders.find { it.id == folder }?.label?.ifEmpty { null } ?: folder

    AppScreen(title = stringResource(R.string.recent_changes), subtitle = label, onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
            val copies = s.conflicts[folder].orEmpty()
            if (copies.isNotEmpty()) {
                item("conflicts-title") {
                    Text(
                        pluralStringResource(CoreR.plurals.conflicts, copies.size, copies.size),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AppColors.busy,
                        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                    )
                }
                item("conflicts") { ConflictsBox(copies, s, s.folders.find { it.id == folder }?.maxConflicts ?: DEFAULT_MAX_CONFLICTS) }
            }
            val shown = changes
            when {
                shown == null -> item("loading") { Hint(stringResource(R.string.loading)) }
                shown.isEmpty() -> item("none") { Hint(stringResource(R.string.recent_none)) }
                else -> {
                    shown.groupBy { dayText(it.time) }.forEach { (day, list) ->
                        item("day-$day") { GroupTitle(day?.asString().orEmpty()) }
                        item("list-$day") {
                            ListCard {
                                list.forEach { ChangeRow(it, s) }
                            }
                        }
                    }
                    item("note") { Hint(pluralStringResource(R.plurals.recent_note, RECENT_LIMIT, grouped(RECENT_LIMIT))) }
                }
            }
        }
    }
}

private const val RECENT_LIMIT = 500

/** Upstream's default for a folder's maxConflicts. */
private const val DEFAULT_MAX_CONFLICTS = 10

/**
 * The folder's conflict copies, framed in amber: per copy the file it came from, when it was made and
 * whose edit was kept, then how many copies the folder keeps. A list only: the user sorts them out.
 */
@Composable
private fun ConflictsBox(copies: List<String>, s: Snapshot, max: Int) {
    val busy = AppColors.busy
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("conflicts"),
        colors = CardDefaults.cardColors(containerColor = busy.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface)),
        border = BorderStroke(1.dp, busy),
    ) {
        val now = LocalNow.current().atZone(ZoneId.systemDefault())
        copies.forEach { path ->
            val c = parseConflictCopy(path)
            val details = listOfNotNull(
                (c?.original ?: path).substringBeforeLast('/', "").ifEmpty { null },
                c?.madeAt?.let { stringResource(R.string.conflict_made, whenText(it.atZone(now.zone).toInstant(), now).asString()) },
                c?.device?.let { id ->
                    if (id == shortId(s.myId)) {
                        stringResource(R.string.conflict_with_this_phone)
                    } else {
                        stringResource(R.string.conflict_with, s.devices.find { shortId(it.deviceID) == id }?.name?.ifEmpty { null } ?: id)
                    }
                },
            )
            SettingRow((c?.original ?: path).substringAfterLast('/'), details.joinToString(" · "), icon = Icons.Default.FileCopy)
        }
        Text(
            when {
                max < 0 -> stringResource(R.string.conflicts_limit_all, grouped(DEFAULT_MAX_CONFLICTS))
                max == 0 -> stringResource(R.string.conflicts_limit_off, grouped(DEFAULT_MAX_CONFLICTS))
                max == DEFAULT_MAX_CONFLICTS -> stringResource(R.string.conflicts_limit_default, grouped(max))
                else -> pluralStringResource(R.plurals.conflicts_limit, max, grouped(max), grouped(DEFAULT_MAX_CONFLICTS))
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun ChangeRow(r: RecentChange, s: Snapshot) {
    val c = r.change
    val deleted = c.action == "deleted"
    val what = if (r.fromThisPhone || c.modifiedBy == shortId(s.myId)) {
        stringResource(if (deleted) R.string.change_deleted_here else R.string.change_changed_here)
    } else {
        val by = s.devices.find { shortId(it.deviceID) == c.modifiedBy }?.name?.ifEmpty { null } ?: c.modifiedBy
        stringResource(if (deleted) R.string.change_deleted_by else R.string.change_changed_by, by)
    }
    SettingRow(
        c.path.substringAfterLast('/'),
        listOfNotNull(c.path.substringBeforeLast('/', "").ifEmpty { null }, what, exactTimeText(r.time).asString()).joinToString(" · "),
        icon = when {
            c.type == "dir" -> Icons.Default.Folder
            deleted -> Icons.Default.Delete
            else -> Icons.Default.Edit
        },
        danger = deleted,
    )
}

private enum class Range(@StringRes val label: Int, @StringRes val newest: Int, val days: Long?) {
    Week(R.string.range_week, R.string.restore_all_newest_week, 7),
    Month(R.string.range_month, R.string.restore_all_newest_month, 30),
    All(R.string.range_all, R.string.restore_all_newest, null),
}

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
        title = stringResource(R.string.restore_versions),
        onBack = onBack,
        actions = { TextButton(enabled = files.isNotEmpty(), onClick = { confirmAll = true }) { Text(stringResource(R.string.restore_all)) } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
            item("filters") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f?.label?.ifEmpty { null } ?: folder, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Range.entries.forEach { r -> FilterChip(range == r, { range = r }, { Text(stringResource(r.label)) }, shape = ButtonShape) }
                    }
                    OutlinedTextField(
                        query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            when {
                versions == null -> item("loading") { Hint(stringResource(R.string.loading)) }
                files.isEmpty() -> item("none") { Hint(stringResource(if (versions!!.isEmpty()) R.string.versions_none else R.string.versions_no_match)) }
            }
            items(files, key = { it.first }) { (path, all, inRange) ->
                ListCard {
                    SettingRow(
                        path.substringAfterLast('/'),
                        listOfNotNull(
                            path.substringBeforeLast('/', "").ifEmpty { null },
                            count(R.plurals.count_versions, all.size).asString(),
                            whenText(inRange.first().versionTime)?.let { stringResource(R.string.versions_latest, it.asString()) },
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
            stringResource(R.string.restore_all_title, count(R.plurals.count_files, files.size).asString()),
            stringResource(R.string.restore_all_text, stringResource(range.newest), restoreNote(f?.type, plural = true)),
            stringResource(R.string.action_restore), onDismiss = { confirmAll = false },
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
                Text("${whenText(v.versionTime)?.asString() ?: v.versionTime} · ${bytes(v.size)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        FilledTonalButton(shape = ButtonShape, onClick = { versions.find { it.versionTime == picked }?.let(onRestore) }, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_restore))
        }
        Text(
            stringResource(R.string.restore_one_text, restoreNote(folderType, plural = false)),
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
@Composable
private fun restoreNote(folderType: String?, plural: Boolean): String = stringResource(
    if (folderType == "receiveonly") if (plural) R.string.restore_note_local_many else R.string.restore_note_local_one
    else if (plural) R.string.restore_note_many else R.string.restore_note_one,
)
