@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package eu.stedb.mysyncthing.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.Folder
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.raw
import eu.stedb.mysyncthing.core.text
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- What the browser shows and allows; plain functions, tested on their own.

/** The storage [path] is on, or null for a path outside every storage. */
fun rootOf(path: String, roots: List<StorageRoot>): StorageRoot? =
    roots.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }

/** Internal storage, else Android's name for the storage ("SanDisk SD card"), else its directory. */
fun rootName(root: StorageRoot): UiText =
    if (root.name == null) text(R.string.internal_storage) else raw(root.name.ifBlank { root.path.substringAfterLast('/') })

/** The storage's name and the rest of the path in it ("" at its top); a path on no storage stays whole. */
fun pathParts(path: String, roots: List<StorageRoot>): Pair<UiText?, String> {
    val p = folderPath(path)
    val root = rootOf(p, roots) ?: return null to p
    return rootName(root) to p.removePrefix(root.path).trimStart('/')
}

/** The directories from the storage's top down to [path], each as its full path. */
fun crumbs(path: String, root: StorageRoot): List<String> =
    listOf(root.path) + path.removePrefix(root.path).split('/').filter { it.isNotEmpty() }
        .runningReduce { a, b -> "$a/$b" }.map { "${root.path}/$it" }

private val Folder.shownName get() = label.ifEmpty { id }

/**
 * A directory in the browser: whether it opens, how it relates to the folders
 * already synced, and whether it is the default location for new folders.
 */
data class BrowseRow(
    val name: String, val path: String, val items: Int?, val locked: Boolean, val syncedAs: String?, val syncedInside: Int,
    val isDefault: Boolean = false,
)

/** The directories of [dir] in name order, dot directories only when [showHidden]; [base] is the default location. */
fun browseRows(dir: String, entries: List<DirEntry>, folders: List<Folder>, showHidden: Boolean, base: String = ""): List<BrowseRow> {
    val synced = folders.map { folderPath(it.path) to it.shownName }
    val b = folderPath(base)
    return entries.filter { showHidden || !it.name.startsWith(".") }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, DirEntry::name).thenBy(DirEntry::name))
        .map { e ->
            val p = "$dir/${e.name}"
            BrowseRow(
                e.name, p, e.items, isAppPrivate(p),
                synced.find { it.first.equals(p, ignoreCase = true) }?.second,
                synced.count { !it.first.equals(p, ignoreCase = true) && pathInside(it.first, p) },
                isDefault = b.isNotEmpty() && b.equals(p, ignoreCase = true),
            )
        }
}

/**
 * Why [path] can't be a new folder, naming the synced folder in the way, or
 * null when it can. The same rules as an import ([folderPathError]), plus the
 * default location for new folders, [base] ([defaultLocationError]).
 */
fun pickProblem(path: String, folders: List<Folder>, base: String): UiText? {
    val p = folderPath(path)
    for (f in folders) {
        val o = folderPath(f.path)
        when {
            p.equals(o, ignoreCase = true) -> return text(R.string.browse_synced_already, f.shownName)
            pathInside(p, o) -> return text(R.string.browse_inside_synced, f.shownName)
            pathInside(o, p) -> return text(R.string.browse_holds_synced, f.shownName)
        }
    }
    return folderPathError(p) ?: defaultLocationError(p, base)
}

/**
 * Where the browser opens: the deepest existing directory of the form's [path],
 * else of [base] (Syncthing/), else the top of internal storage.
 */
fun startDir(path: String, base: String, roots: List<StorageRoot>, disk: Disk): String {
    for (candidate in listOf(folderPath(path), folderPath(base))) {
        val root = rootOf(candidate, roots) ?: continue
        var p = candidate
        while (p != root.path && (isAppPrivate(p) || !disk.isDirectory(p))) p = p.substringBeforeLast('/')
        return p
    }
    return roots.firstOrNull()?.path ?: internalStorage
}

/** Characters FAT and exFAT, the usual file systems of SD cards and USB sticks, don't take. */
private const val NOT_ON_REMOVABLE = "\\:*?\"<>|"

/**
 * Why [name] can't be a new directory next to [existing], or null. On a
 * [removable] storage also FAT's rules. Internal storage ignores case, so
 * "download" is taken where "Download" is.
 */
fun newFolderNameError(name: String, existing: Collection<String>, removable: Boolean): UiText? {
    val n = name.trim()
    return when {
        n.isEmpty() -> text(R.string.error_required)
        n.any { it.isISOControl() || it in BIDI_CONTROLS } -> text(R.string.error_control_characters)
        '/' in n -> text(R.string.error_id_slash)
        n == "." || n == ".." -> text(R.string.error_id_dots)
        n.toByteArray().size > 255 -> text(R.string.error_name_long)
        removable && (n.any { it in NOT_ON_REMOVABLE } || n.endsWith('.')) -> text(R.string.error_name_removable, NOT_ON_REMOVABLE.toList().joinToString(" "))
        existing.any { it.equals(n, ignoreCase = true) } -> text(R.string.error_name_taken)
        else -> null
    }
}

/**
 * The usual folders to sync, as one-tap choices: the camera, screenshots (in
 * Pictures on Pixel phones, in DCIM on several other brands), pictures,
 * downloads, documents and music. Only those that exist on [root].
 */
fun quickPicks(disk: Disk, root: String = internalStorage): List<String> = QUICK_PICKS
    .mapNotNull { candidates -> candidates.map { "$root/$it" }.firstOrNull(disk::isDirectory) }

/**
 * Directory names as Android makes them in every language (Environment.DIRECTORY_*, which
 * are not constants a unit test can read), and the camera app's, which has no constant.
 */
private val QUICK_PICKS = listOf(
    listOf("DCIM/Camera"),
    listOf("Pictures/Screenshots", "DCIM/Screenshots"),
    listOf("Pictures"),
    listOf("Download"),
    listOf("Documents"),
    listOf("Music"),
)

// --- The screen.

/**
 * Full-screen folder browser over the phone's storages, with the All files
 * access the app already has: unlike Android's own picker it can choose
 * Download and the top of a storage. It opens at the form's [path], else at
 * [base] ([startDir]); [onPick] gets the chosen path.
 */
@Composable
fun FolderBrowser(path: String, base: String, folders: List<Folder>, onBack: () -> Unit, onPick: (String) -> Unit) {
    val disk = rememberDisk()
    // Kept over a rotation; the storages are read again, off the main thread like every disk read here.
    var dir by rememberSaveable { mutableStateOf<String?>(null) }
    var roots by remember { mutableStateOf<List<StorageRoot>?>(null) }
    LaunchedEffect(Unit) {
        val (r, start) = withContext(disk.reads) { disk.roots().let { it to startDir(path, base, it, disk) } }
        if (dir == null) dir = start
        roots = r
    }
    BackHandler(onBack = onBack)
    val here = dir
    val all = roots
    if (here == null || all.isNullOrEmpty()) {
        AppScreen(title = stringResource(R.string.browse_title), onBack = onBack) { padding -> Box48(Modifier.padding(padding)) { BusyIndicator() } }
        return
    }
    BrowserContent(disk, here, all, folders, onOpen = { dir = it }, onBack = onBack, onPick = onPick)
}

@Composable
private fun BrowserContent(
    disk: Disk, dir: String, roots: List<StorageRoot>, folders: List<Folder>, onOpen: (String) -> Unit, onBack: () -> Unit, onPick: (String) -> Unit,
) {
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<BrowseDialog?>(null) }
    var makeFailed by rememberSaveable { mutableStateOf(false) }
    // Bumped after a failed New folder, so the listing is read again.
    var reload by rememberSaveable { mutableIntStateOf(0) }
    // The last listing read, with its directory: rows of the folder left behind are never shown
    // under the new one, and the list is only laid out once its rows are there, so it opens
    // at its top (or, after a rotation, where it was) rather than one row down.
    var listing by remember { mutableStateOf<Pair<String, List<DirEntry>?>?>(null) }
    LaunchedEffect(dir, reload) { listing = dir to withContext(disk.reads) { disk.directories(dir) } }
    val loaded = listing?.first == dir
    val entries = listing?.takeIf { loaded }?.second
    val open = { p: String -> makeFailed = false; onOpen(p) }
    val scope = rememberCoroutineScope()
    val root = rootOf(dir, roots) ?: roots.first()
    val rows = entries?.let { browseRows(dir, it, folders, showHidden, base) }
    // Each directory opens at its top; the position is kept over a rotation.
    val list = rememberSaveable(dir, saver = LazyListState.Saver) { LazyListState() }
    // Hidden folders sort first: once they are listed, the list goes to the top to show them.
    var toTop by remember { mutableStateOf(false) }
    LaunchedEffect(rows) { if (toTop) { list.scrollToItem(0); toTop = false } }
    val problem = if (loaded && entries == null) text(R.string.browse_unreadable) else pickProblem(dir, folders, base)

    AppScreen(
        title = stringResource(R.string.browse_title), onBack = onBack,
        actions = {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }, Modifier.testTag("browseMenu")) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.browse_new_folder)) }, enabled = entries != null, onClick = { menu = false; dialog = BrowseDialog.NewFolder })
                DropdownMenuItem(text = { Text(stringResource(R.string.browse_type_path)) }, onClick = { menu = false; dialog = BrowseDialog.TypePath })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browse_show_hidden)) },
                    trailingIcon = { Checkbox(showHidden, onCheckedChange = null) },
                    onClick = { menu = false; showHidden = !showHidden; toTop = showHidden },
                )
            }
        },
    ) { padding ->
        val storages = @Composable {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                roots.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = r == root, onClick = { open(r.path) },
                        shape = SegmentedButtonDefaults.itemShape(i, roots.size, MaterialTheme.shapes.extraSmall),
                        icon = {},
                    ) {
                        Column {
                            Text(rootName(r).asString(), maxLines = 2)
                            // The button's own text colour: the selected one is secondaryContainer,
                            // where onSurfaceVariant is no pair (unreadable with high contrast).
                            r.free?.let { Text(stringResource(R.string.browse_free, bytes(it)), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            Crumbs(dir, root, open)
        }
        val listing = @Composable { modifier: Modifier ->
            Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = AppColors.card(), border = AppColors.cardBorder()) {
                if (!loaded) Box48 { BusyIndicator() } else LazyColumn(Modifier.testTag("browseList"), state = list) {
                    when {
                        rows == null -> item { Hint(stringResource(R.string.browse_unreadable)) }
                        rows.isEmpty() -> item { Hint(stringResource(R.string.browse_empty)) }
                    }
                    items(rows.orEmpty(), key = { it.path }) { row ->
                        BrowseRowItem(row) { open(row.path) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (entries != null) item {
                        ListItem(
                            modifier = Modifier.clickable { dialog = BrowseDialog.NewFolder }.testTag("browseNewFolder"),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Icon(Icons.Default.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(stringResource(R.string.browse_new_folder), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) },
                        )
                    }
                }
            }
        }
        val choice = @Composable { modifier: Modifier ->
            Surface(color = AppColors.card().containerColor, modifier = modifier) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (top, rest) = pathParts(dir, roots)
                    Text(
                        (if (makeFailed) text(R.string.browse_make_failed) else problem ?: text(R.string.browse_sync_this)).asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (makeFailed || problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(if (rest.isEmpty()) top?.asString() ?: dir else "/$rest", style = MaterialTheme.typography.bodyLarge)
                    Button(
                        onClick = { onPick(dir) }, enabled = loaded && problem == null, shape = ButtonShape,
                        modifier = Modifier.fillMaxWidth().testTag("browseUse"),
                    ) { Text(stringResource(R.string.browse_use)) }
                }
            }
        }
        BoxWithConstraints(Modifier.padding(padding)) {
            // A phone on its side: the choice beside the list, which keeps the height.
            val side = maxWidth * 0.4f
            if (maxWidth > maxHeight && maxHeight < 480.dp) Row {
                Column(Modifier.weight(1f)) { storages(); listing(Modifier.weight(1f)) }
                choice(Modifier.fillMaxHeight().width(side).verticalScroll(rememberScrollState()))
            } else Column {
                storages()
                listing(Modifier.weight(1f))
                choice(Modifier.fillMaxWidth())
            }
        }
    }

    when (dialog) {
        BrowseDialog.NewFolder -> TextDialog(
            stringResource(R.string.browse_new_folder), "", stringResource(R.string.name),
            error = { newFolderNameError(it, entries.orEmpty().map(DirEntry::name), root.name != null) },
            onDismiss = { dialog = null },
        ) { name ->
            dialog = null
            val made = "$dir/${name.trim()}"
            scope.launch {
                if (withContext(disk.reads) { disk.makeDirectory(made) }) {
                    open(made)
                } else {
                    makeFailed = true
                    reload++
                }
            }
        }
        BrowseDialog.TypePath -> TextDialog(
            stringResource(R.string.browse_type_path), dir, stringResource(R.string.browse_path),
            error = { pickProblem(it, folders, base) },
            onDismiss = { dialog = null },
        ) { dialog = null; onPick(folderPath(it)) }
        null -> {}
    }
}

private enum class BrowseDialog { NewFolder, TypePath }

/** The path from the storage's top, each step tappable. */
@Composable
private fun Crumbs(dir: String, root: StorageRoot, onOpen: (String) -> Unit) {
    val steps = crumbs(dir, root)
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
        steps.forEachIndexed { i, p ->
            val last = i == steps.lastIndex
            val name = if (i == 0) rootName(root).asString() else p.substringAfterLast('/')
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (last) FontWeight.Medium else FontWeight.Normal,
                    color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.heightIn(min = 32.dp).let { if (last) it else it.clickable { onOpen(p) } }.padding(vertical = 6.dp, horizontal = 2.dp),
                )
                if (!last) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BrowseRowItem(row: BrowseRow, onOpen: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    // Everything but the arrow under the name: beside it, a count squeezed long names at large font sizes.
    val lines = buildList<@Composable () -> Unit> {
        if (row.isDefault && !row.locked) add { Text(stringResource(R.string.browse_default_location), color = MaterialTheme.colorScheme.primary) }
        when {
            row.locked -> add { Text(stringResource(R.string.error_path_app_private)) }
            row.syncedAs != null -> add {
                Row {
                    Icon(Icons.Default.Sync, null, Modifier.padding(top = 2.dp, end = 4.dp).size(16.dp), tint = AppColors.ok)
                    Text(stringResource(R.string.browse_synced_as, row.syncedAs), color = AppColors.ok)
                }
            }
            row.syncedInside > 0 -> add { Text(count(R.plurals.browse_synced_inside, row.syncedInside).asString()) }
        }
        if (!row.locked) row.items?.let { add { Text(count(R.plurals.count_items, it).asString()) } }
    }
    ListItem(
        modifier = if (row.locked) Modifier else Modifier.clickable(onClick = onOpen),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(if (row.locked) Icons.Default.Lock else Icons.Default.Folder, null, tint = if (row.locked) dim else MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(row.name, color = if (row.locked) dim else Color.Unspecified) },
        supportingContent = if (lines.isEmpty()) null else { { Column { lines.forEach { it() } } } },
        trailingContent = if (row.locked) null else { { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = dim) } },
    )
}

@Composable
private fun Hint(s: String) = Text(s, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))

@Composable
private fun Box48(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(16.dp), horizontalArrangement = Arrangement.Center) { content() }
