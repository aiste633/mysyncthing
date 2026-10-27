package eu.stedb.mysyncthing.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.stedb.mysyncthing.core.problemText
import eu.stedb.mysyncthing.core.FileEntry
import eu.stedb.mysyncthing.core.FileError
import eu.stedb.mysyncthing.core.NeedPage
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.SyncState

/** Files this phone still has to download for one folder (web GUI: "Out of Sync Items"). */
@Composable
fun NeedScreen(s: Snapshot, folder: String, vm: MainViewModel, onBack: () -> Unit) {
    val f = s.folders.find { it.id == folder }
    val st = s.folderStatus[folder]
    var limit by rememberSaveable { mutableIntStateOf(PAGE) }
    var page by remember { mutableStateOf<NeedPage?>(null) }
    LaunchedEffect(folder, limit, st?.needTotalItems, st?.needBytes) { vm.need(folder, limit)?.let { page = it } }
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val progress = downloads[folder].orEmpty()
    val shown = page?.let { it.progress.orEmpty().size + it.queued.orEmpty().size + it.rest.orEmpty().size } ?: 0
    val status = if (f != null && st != null) folderStatus(f, st) else null

    // A send-only folder never downloads: the list is what other devices changed.
    val sendOnly = f?.type == "sendonly"
    FileList(if (sendOnly) "Changed on other devices" else "Still to download", onBack) {
        item("header") {
            val left = st?.let { "${count(it.needTotalItems, "item")} · ${bytes(it.needBytes)}" + if (sendOnly) "" else " left" }
            Header(f?.label?.ifEmpty { null } ?: folder, left, status)
        }
        val p = page
        if (p == null) {
            item("loading") { Note("Loading") }
            return@FileList
        }
        if (shown == 0) item("none") { Note(if (sendOnly) "No changes from other devices." else "Nothing left to download.") }
        // The puller marks a whole batch "in progress" at once; only files with
        // download progress are really transferring, the rest wait their turn.
        val (active, started) = p.progress.orEmpty().partition { it.name in progress }
        section("Downloading now", active) { e ->
            val d = progress.getValue(e.name)
            FileRow(e.name, "${bytes(d.bytesDone)} of ${bytes(d.bytesTotal)}",
                progress = d.takeIf { it.bytesTotal > 0 }?.let { it.bytesDone.toFloat() / it.bytesTotal })
        }
        section("Waiting", started + p.queued.orEmpty() + p.rest.orEmpty()) { e -> FileRow(e.name, needDetail(e)) }
        if (shown >= limit) item("more") { More { limit += PAGE } }
    }
}

/** Files [device] still needs from this phone in one folder (web GUI: the device's "Out of Sync Items"). */
@Composable
fun RemoteNeedScreen(s: Snapshot, device: String, folder: String, vm: MainViewModel, onBack: () -> Unit) {
    val d = s.devices.find { it.deviceID == device }
    val f = s.folders.find { it.id == folder }
    var limit by rememberSaveable { mutableIntStateOf(PAGE) }
    var files by remember { mutableStateOf<List<FileEntry>?>(null) }
    LaunchedEffect(device, folder, limit, s.completion[device]) { vm.remoteNeed(folder, device, limit)?.let { files = it } }
    val name = d?.name?.ifEmpty { null } ?: shortId(device)

    FileList("$name still needs", onBack) {
        item("header") { Header(f?.label?.ifEmpty { null } ?: folder, files?.let { "${count(it.size, "item")} · ${bytes(it.sumOf { e -> e.size })}" + if (it.size >= limit) " or more" else "" }) }
        val list = files
        when {
            list == null -> item("loading") { Note("Loading") }
            list.isEmpty() -> item("none") { Note("$name has everything from this folder.") }
            else -> {
                section(null, list) { e -> FileRow(e.name, needDetail(e)) }
                if (list.size >= limit) item("more") { More { limit += PAGE } }
            }
        }
    }
}

/** What changed on this phone in a receive-only folder (web GUI: "Locally Changed Items"). */
@Composable
fun LocalChangesScreen(s: Snapshot, folder: String, vm: MainViewModel, onBack: () -> Unit) {
    val f = s.folders.find { it.id == folder }
    val st = s.folderStatus[folder]
    var limit by rememberSaveable { mutableIntStateOf(PAGE) }
    var files by remember { mutableStateOf<List<FileEntry>?>(null) }
    LaunchedEffect(folder, limit, st?.receiveOnlyTotalItems) { vm.localChanged(folder, limit)?.let { files = it } }

    FileList("Changed on this phone", onBack) {
        item("header") {
            Header(f?.label?.ifEmpty { null } ?: folder, st?.let { "${count(it.receiveOnlyTotalItems, "item")} · ${bytes(it.receiveOnlyChangedBytes)}" })
        }
        val list = files
        when {
            list == null -> item("loading") { Note("Loading") }
            list.isEmpty() -> item("none") { Note("No local changes.") }
            else -> {
                section(null, list) { e -> FileRow(e.name, if (e.deleted) "Deleted" else needDetail(e)) }
                if (list.size >= limit) item("more") { More { limit += PAGE } }
            }
        }
    }
}

/** Every item that failed to sync in one folder, searchable, with Copy all for bug reports. */
@Composable
fun FailedScreen(s: Snapshot, folder: String, vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val f = s.folders.find { it.id == folder }
    val st = s.folderStatus[folder]
    var errors by remember { mutableStateOf<List<FileError>?>(null) }
    LaunchedEffect(folder, st?.errors) { errors = vm.folderErrors(folder) }
    var query by rememberSaveable { mutableStateOf("") }
    val all = errors.orEmpty()
    val shown = if (query.isBlank()) all else all.filter { failedItemMatches(it, query) }

    FileList("Failed items", onBack, actions = {
        TextButton(enabled = all.isNotEmpty(), onClick = {
            val text = failedItemsCopy(all)
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Failed items", text))
        }) { Text("Copy all") }
    }) {
        item("header") { Header(f?.label?.ifEmpty { null } ?: folder, errors?.let { "${count(it.size, "item")} could not sync" }) }
        item("search") {
            OutlinedTextField(
                query, { query = it }, singleLine = true, label = { Text("Search") },
                placeholder = { Text("Name or error") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (errors == null) item("loading") { Note("Loading") }
        else if (shown.isEmpty()) item("none") { Note(if (all.isEmpty()) "Nothing failed." else "No match.") }
        section(null, shown) { e -> FileRow(e.path, problemText(e.error), detailColor = MaterialTheme.colorScheme.error) }
    }
}

private const val PAGE = 200

@Composable
private fun FileList(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: LazyListScope.() -> Unit) {
    AppScreen(title = title, onBack = onBack, actions = actions) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp), content = content)
    }
}

/** A heading followed by one compact card per file; the list can hold thousands, so each row is its own item. */
private fun <T> LazyListScope.section(title: String?, files: List<T>, row: @Composable (T) -> Unit) {
    if (files.isEmpty()) return
    title?.let { item("title-$it") { SectionTitle(it) } }
    items(files) { row(it) }
}

@Composable
private fun SectionTitle(title: String) = Text(
    title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 4.dp),
)

@Composable
private fun Header(folder: String, detail: String?, status: ItemStatus? = null) = ListCard {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        status?.let { StatusLine(it) }
        Text(folder, style = MaterialTheme.typography.titleMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        status?.progress?.let { ProgressBar(it, status.state.look().color) }
    }
}

@Composable
private fun StatusLine(status: ItemStatus) {
    val look = status.state.look()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(look.icon, null, Modifier.size(18.dp), tint = look.color)
        Spacer(Modifier.width(6.dp))
        Text(status.text, style = MaterialTheme.typography.labelLarge, color = look.color)
    }
}

@Composable
private fun FileRow(name: String, detail: String, progress: Float? = null, detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    ListCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor)
            progress?.let { ProgressBar(it, SyncState.Syncing.look().color, Modifier.padding(top = 4.dp)) }
        }
    }

@Composable
private fun Note(text: String) = Text(
    text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
)

@Composable
private fun More(onClick: () -> Unit) = TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
    Text("Show more")
}

private fun needDetail(e: FileEntry) = when {
    e.deleted -> "To delete"
    e.isDirectory -> "Folder"
    else -> bytes(e.size)
}

/** "Copy all" of Failed items: the core's own texts, which a bug report or a forum needs, not [problemText]'s. */
internal fun failedItemsCopy(errors: List<FileError>): String = errors.joinToString("\n") { "${it.path}: ${it.error}" }

/** Search in Failed items finds a row by its path, the core's text, or the text shown for it. */
internal fun failedItemMatches(e: FileError, query: String): Boolean =
    query.trim().lowercase() in (e.path + " " + e.error + " " + problemText(e.error)).lowercase()
