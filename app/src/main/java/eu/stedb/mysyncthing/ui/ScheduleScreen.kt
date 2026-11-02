package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.MAX_SYNC_WINDOWS
import eu.stedb.mysyncthing.core.withDeviceWindows
import eu.stedb.mysyncthing.core.nextText
import eu.stedb.mysyncthing.core.neverSyncing
import eu.stedb.mysyncthing.core.mask
import eu.stedb.mysyncthing.core.globalMask
import eu.stedb.mysyncthing.core.WeekMask
import eu.stedb.mysyncthing.core.Stretch
import eu.stedb.mysyncthing.core.Snapshot
import eu.stedb.mysyncthing.core.MINUTES_PER_WEEK
import eu.stedb.mysyncthing.core.MINUTES_PER_DAY
import eu.stedb.mysyncthing.core.DeviceWindows
import eu.stedb.mysyncthing.core.NO_WINDOW
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.SyncWindow
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.joined
import eu.stedb.mysyncthing.core.num
import eu.stedb.mysyncthing.core.plural
import eu.stedb.mysyncthing.core.raw
import eu.stedb.mysyncthing.core.scheduleError
import eu.stedb.mysyncthing.core.shortDay
import eu.stedb.mysyncthing.core.syncWindowError
import eu.stedb.mysyncthing.core.text
import eu.stedb.mysyncthing.core.timeText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Sync windows: syncing only in time windows of the week. For all devices ([device] null, from
 * the Devices tab), or a device's own, which narrow the global ones ([RunConditions.mask]).
 */
@Composable
fun ScheduleScreen(vm: MainViewModel, s: Snapshot, device: String? = null, onBack: () -> Unit) {
    // Read from storage first, so the form starts from what is saved.
    val stored by vm.storedRunConditions.collectAsStateWithLifecycle()
    val names = s.devices.associate { it.deviceID to it.name.ifEmpty { shortId(it.deviceID) } }
    stored?.let { ScheduleForm(it, device, names, vm::saveRunConditions, onBack) }
}

/** What the form is: the global windows, or [device]'s own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleForm(
    initial: RunConditions,
    device: String?,
    names: Map<String, String>,
    save: suspend ((RunConditions) -> RunConditions) -> Boolean,
    onBack: () -> Unit,
) {
    val own = device?.let { initial.deviceWindows[it] ?: DeviceWindows() }
    val initialOn = own?.on ?: initial.timeSchedule
    val initialWindows = own?.windows ?: initial.windows
    var on by rememberSaveable { mutableStateOf(initialOn) }
    // Saved as JSON: survives rotation and process death.
    var saved by rememberSaveable { mutableStateOf(Json.encodeToString(initialWindows)) }
    val windows = remember(saved) { Json.decodeFromString<List<SyncWindow>>(saved) }
    fun edit(ws: List<SyncWindow>) { saved = Json.encodeToString(ws) }
    var picking by rememberSaveable { mutableStateOf<Pair<Int, Boolean>?>(null) } // window, start (else end)
    var saving by remember { mutableStateOf(false) }
    // Devices the new global windows would leave with no time to sync, until the user confirms.
    var confirming by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()

    fun apply(c: RunConditions): RunConditions =
        if (device == null) c.copy(timeSchedule = on, windows = windows) else c.withDeviceWindows(device, DeviceWindows(on, windows))
    val changed = on != initialOn || windows != initialWindows
    val error = scheduleError(on, windows)
    // What the form gives, judged together with the rest as stored now.
    val result = apply(initial)
    val mask = if (device == null) result.globalMask() else result.mask(device)
    val never = device != null && on && error == null && mask.isEmpty
    val back = rememberDiscardGuard(changed, onBack)
    fun store() {
        saving = true
        scope.launch { if (save(::apply)) onBack() else saving = false }
    }

    AppScreen(
        title = stringResource(R.string.sync_times),
        subtitle = device?.let { names[it] ?: shortId(it) },
        onBack = back,
        actions = {
            TextButton(enabled = changed && error == null && !never && !saving, modifier = Modifier.testTag("saveWindows"), onClick = {
                // Devices that sync now but never would with these global windows: the user decides.
                val lost = if (device == null) (result.neverSyncing() - initial.neverSyncing()).filter { it in names } else emptyList()
                if (lost.isEmpty()) store() else confirming = lost
            }) { Text(stringResource(R.string.action_save)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (device != null) {
                GroupTitle(stringResource(R.string.device_windows_global))
                GlobalWindowsCard(initial)
                GroupTitle(stringResource(R.string.this_device))
            }
            ListCard {
                if (device == null) {
                    SwitchRow(
                        stringResource(R.string.schedule_only_at_set_times), stringResource(if (on) R.string.schedule_on_hint else R.string.schedule_off_hint),
                        on, Icons.Default.Schedule,
                    ) { on = it }
                } else {
                    Box(Modifier.testTag("ownWindows")) {
                        SwitchRow(
                            stringResource(R.string.device_windows_own),
                            stringResource(if (on) R.string.device_windows_on_hint else R.string.device_windows_off_hint),
                            on, Icons.Default.Schedule,
                        ) { on = it }
                    }
                }
            }
            windows.forEachIndexed { i, w ->
                SettingsGroup(stringResource(R.string.schedule_window_title, i + 1)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // The times wrap at large font sizes, so Delete keeps its place at the end.
                            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(shape = ButtonShape, onClick = { picking = i to true }) { Text(stringResource(R.string.schedule_from, timeText(w.start).asString())) }
                                OutlinedButton(shape = ButtonShape, onClick = { picking = i to false }) { Text(stringResource(R.string.schedule_to, timeText(w.end).asString())) }
                            }
                            IconButton(onClick = { edit(windows.filterIndexed { j, _ -> j != i }) }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.schedule_delete_window, i + 1))
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DayOfWeek.entries.forEach { d ->
                                val picked = d.value in w.days
                                FilterChip(
                                    picked,
                                    { edit(windows.replace(i, w.copy(days = if (picked) w.days - d.value else w.days + d.value))) },
                                    { Text(shortDay(d).asString()) }, shape = ButtonShape,
                                )
                            }
                        }
                        Text(
                            (syncWindowError(w) ?: windowText(w)).asString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (syncWindowError(w) != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                OutlinedButton(shape = ButtonShape, enabled = windows.size < MAX_SYNC_WINDOWS, onClick = { edit(windows + SyncWindow.NIGHT) }) {
                    Icon(Icons.Default.Add, null)
                    Text(stringResource(R.string.schedule_add_window), Modifier.padding(start = 8.dp))
                }
            }
            if (error == NO_WINDOW) {
                Text(
                    stringResource(if (device == null) R.string.schedule_no_window_fix else R.string.device_windows_no_window_fix),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
            if (on && error == null) WindowsResult(mask)
            Text(
                stringResource(R.string.schedule_note),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
    }

    picking?.let { (i, start) ->
        val w = windows.getOrNull(i) ?: return@let
        TimeDialog(stringResource(if (start) R.string.schedule_from_title else R.string.schedule_to_title), if (start) w.start else w.end, onDismiss = { picking = null }) { m ->
            picking = null
            edit(windows.replace(i, if (start) w.copy(start = m) else w.copy(end = m)))
        }
    }
    confirming?.let { lost ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.schedule_never_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.schedule_never_text))
                    Text(lost.map { names[it] ?: shortId(it) }.sorted().joinToString(", "), fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.schedule_never_after))
                }
            },
            confirmButton = {
                TextButton(onClick = { confirming = null; store() }, modifier = Modifier.testTag("saveAnyway")) { Text(stringResource(R.string.schedule_save_anyway)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * The global windows on a device's own windows screen, to read only: in the This device card's
 * colour, the one for things that are shown, not changed here.
 */
@Composable
private fun GlobalWindowsCard(c: RunConditions) {
    val on = MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("globalWindows"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = on),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, null, tint = on)
            Column(Modifier.padding(start = 16.dp)) {
                Text(stringResource(if (c.timeSchedule) R.string.schedule_only_at_set_times else R.string.any_time), style = MaterialTheme.typography.bodyLarge)
                if (c.timeSchedule) c.windows.forEach { Text(windowText(it).asString(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/**
 * When the windows really let the device (or every device) sync: green with the hours a week and
 * the next start, amber when a stretch is under 1 h (it may pass without a sync), red when never
 * (only a device's own windows can be that: global windows are never empty).
 */
@Composable
private fun WindowsResult(mask: WeekMask) {
    val short = mask.shortStretches()
    val (color, icon) = when {
        mask.isEmpty -> AppColors.problem to Icons.Default.Error
        short.isNotEmpty() -> AppColors.busy to Icons.Default.Warning
        else -> AppColors.ok to Icons.Default.CheckCircle
    }
    // Only the global windows can be "any time"; they then need no note.
    if (mask == WeekMask.ALWAYS) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("windowsResult"),
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = RESULT_ALPHA).compositeOver(AppColors.card().containerColor),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color)
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                if (mask.isEmpty) {
                    Text(stringResource(R.string.windows_never), color = color, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.windows_never_own), color = muted)
                    return@Column
                }
                Text(
                    if (short.isEmpty()) stringResource(R.string.windows_week, weekText(mask.minutes).asString()) else stringResource(R.string.windows_short_title),
                    color = color, style = MaterialTheme.typography.titleSmall,
                )
                if (short.isEmpty()) {
                    effectiveText(mask).forEach { Text(it.asString(), color = muted) }
                    val now = LocalNow.current()
                    mask.nextOpen(now)?.let { Text(nextText(R.string.windows_next, R.string.windows_next_day, it, now).asString(), color = muted) }
                } else {
                    short.take(MAX_LINES).forEach { Text(stretchText(it).asString(), color = muted) }
                    if (short.size > MAX_LINES) Text(pluralStringResource(R.plurals.windows_more, short.size - MAX_LINES, short.size - MAX_LINES), color = muted)
                    Text(stringResource(R.string.windows_short_text), color = muted)
                }
            }
        }
    }
}

private const val RESULT_ALPHA = 0.12f
private const val MAX_LINES = 4

/**
 * When [mask] lets the device sync, as windows: the stretches that start at the same time and
 * last as long are one line with their days ("Mon–Fri 22:00–23:00"); at most four lines.
 */
fun effectiveText(mask: WeekMask): List<UiText> {
    val lines = effectiveWindows(mask).map(::windowText) + mask.stretches().filter { it.length >= MINUTES_PER_DAY }.map(::stretchText)
    return if (lines.size <= MAX_LINES) lines else lines.take(MAX_LINES - 1) + plural(R.plurals.windows_more, lines.size - MAX_LINES + 1, lines.size - MAX_LINES + 1)
}

/**
 * [mask]'s stretches shorter than a day as windows, the ones with the same start and length
 * together, in the week's order. Longer stretches don't fit a window ([effectiveText] names them).
 */
internal fun effectiveWindows(mask: WeekMask): List<SyncWindow> = mask.stretches().filter { it.length < MINUTES_PER_DAY }
    .groupBy { it.start % MINUTES_PER_DAY to it.length }
    .map { (k, list) -> SyncWindow(k.first, (k.first + k.second) % MINUTES_PER_DAY, list.mapTo(LinkedHashSet()) { it.start / MINUTES_PER_DAY + 1 }) }
    .sortedBy { it.days.min() * MINUTES_PER_DAY + it.start }

/**
 * How long a week lets a device sync, exact and in hours, never rounded up to days: "24 h 30 min",
 * "168 h", "45 min".
 */
fun weekText(minutes: Int): UiText {
    val h = minutes / 60
    val m = minutes % 60
    return listOfNotNull(withUnit(num(h), "h").takeIf { h > 0 }, withUnit(num(m), "min").takeIf { m > 0 || h == 0 }).joined(" ")
}

/** "Tue 06:30–07:00", or "Fri 22:00–Mon 07:00" for a stretch that ends on another day. */
fun stretchText(s: Stretch): UiText {
    if (s.length >= MINUTES_PER_WEEK) return text(R.string.any_time)
    fun day(m: Int) = shortDay(DayOfWeek.of(m / MINUTES_PER_DAY + 1))
    val endAt = s.start + s.length
    return if (s.start % MINUTES_PER_DAY + s.length <= MINUTES_PER_DAY) {
        text(R.string.stretch_same_day, day(s.start), timeText(s.start % MINUTES_PER_DAY), timeText(endAt % MINUTES_PER_DAY))
    } else {
        text(R.string.stretch_other_day, day(s.start), timeText(s.start % MINUTES_PER_DAY), day(endAt % MINUTES_PER_WEEK), timeText(endAt % MINUTES_PER_DAY))
    }
}

private fun <T> List<T>.replace(i: Int, item: T) = mapIndexed { j, x -> if (j == i) item else x }

/** A time picker with the phone's 12 or 24 h clock; returns minutes after midnight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, minutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = currentStrings().is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        // The dial is taller than a phone in landscape: there, typing the time (as Material does).
        text = { if (TimePickerDefaults.layoutType() == TimePickerLayoutType.Horizontal) TimeInput(state) else TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "Mon–Fri 22:00–07:00 (next day)": a window for people. */
fun windowText(w: SyncWindow): UiText =
    text(if (w.end < w.start) R.string.schedule_window_next_day else R.string.schedule_window, daysText(w.days), timeText(w.start), timeText(w.end))

/** Days as runs: "Mon–Fri", "Sat, Sun", "Every day", "Mon, Wed–Fri". */
fun daysText(days: Set<Int>): UiText {
    if (days.size == 7) return text(R.string.every_day)
    val runs = mutableListOf<IntRange>()
    for (d in days.sorted()) {
        val last = runs.lastOrNull()
        if (last != null && last.last == d - 1) runs[runs.size - 1] = last.first..d else runs += d..d
    }
    fun name(d: Int) = shortDay(DayOfWeek.of(d))
    return runs.map { r ->
        when (r.last - r.first) {
            0 -> name(r.first)
            1 -> listOf(name(r.first), name(r.last)).joined(", ")
            else -> listOf(name(r.first), name(r.last)).joined("–")
        }
    }.joined(", ")
}

/** The Settings row's summary of the schedule. */
fun scheduleText(c: RunConditions): UiText = when {
    !c.timeSchedule -> text(R.string.any_time)
    c.windows.size == 1 -> windowText(c.windows.single())
    else -> plural(R.plurals.schedule_and_more, c.windows.size - 1, windowText(c.windows.first()), c.windows.size - 1)
}
