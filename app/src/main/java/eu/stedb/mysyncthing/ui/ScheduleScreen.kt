package eu.stedb.mysyncthing.ui

import androidx.compose.ui.res.stringResource
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.plural
import eu.stedb.mysyncthing.core.raw
import eu.stedb.mysyncthing.core.text
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.MAX_SYNC_WINDOWS
import eu.stedb.mysyncthing.core.NO_WINDOW
import eu.stedb.mysyncthing.core.RunConditions
import eu.stedb.mysyncthing.core.SyncWindow
import eu.stedb.mysyncthing.core.scheduleError
import eu.stedb.mysyncthing.core.shortDay
import eu.stedb.mysyncthing.core.syncWindowError
import eu.stedb.mysyncthing.core.timeText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.DayOfWeek

/** Settings > Sync times: syncing only in time windows of the week, for all folders and devices. */
@Composable
fun ScheduleScreen(vm: MainViewModel, onBack: () -> Unit) {
    // Read from storage first, so the form starts from what is saved.
    val stored by produceState<RunConditions?>(null) { value = vm.loadRunConditions() }
    stored?.let { ScheduleForm(it, vm::saveRunConditions, onBack) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleForm(initial: RunConditions, save: suspend ((RunConditions) -> RunConditions) -> Boolean, onBack: () -> Unit) {
    var on by rememberSaveable { mutableStateOf(initial.timeSchedule) }
    // Saved as JSON: survives rotation and process death.
    var saved by rememberSaveable { mutableStateOf(Json.encodeToString(initial.windows)) }
    val windows = remember(saved) { Json.decodeFromString<List<SyncWindow>>(saved) }
    fun edit(ws: List<SyncWindow>) { saved = Json.encodeToString(ws) }
    var picking by rememberSaveable { mutableStateOf<Pair<Int, Boolean>?>(null) } // window, start (else end)
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val changed = on != initial.timeSchedule || windows != initial.windows
    val error = scheduleError(on, windows)
    val back = rememberDiscardGuard(changed, onBack)

    AppScreen(
        title = stringResource(R.string.sync_times),
        onBack = back,
        actions = {
            TextButton(enabled = changed && error == null && !saving, onClick = {
                saving = true
                scope.launch { if (save { it.copy(timeSchedule = on, windows = windows) }) onBack() else saving = false }
            }) { Text(stringResource(R.string.action_save)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ListCard {
                SwitchRow(
                    stringResource(R.string.schedule_only_at_set_times), stringResource(if (on) R.string.schedule_on_hint else R.string.schedule_off_hint),
                    on, Icons.Default.Schedule,
                ) { on = it }
            }
            windows.forEachIndexed { i, w ->
                SettingsGroup(stringResource(R.string.schedule_window_title, i + 1)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(shape = ButtonShape, onClick = { picking = i to true }) { Text(stringResource(R.string.schedule_from, timeText(w.start))) }
                            OutlinedButton(shape = ButtonShape, onClick = { picking = i to false }) { Text(stringResource(R.string.schedule_to, timeText(w.end))) }
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { edit(windows.filterIndexed { j, _ -> j != i }) }) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.schedule_delete_window, i + 1))
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DayOfWeek.entries.forEach { d ->
                                val picked = d.value in w.days
                                FilterChip(
                                    picked,
                                    { edit(windows.replace(i, w.copy(days = if (picked) w.days - d.value else w.days + d.value))) },
                                    { Text(shortDay(d)) }, shape = ButtonShape,
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
                    stringResource(R.string.schedule_no_window_fix),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
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
}

private fun <T> List<T>.replace(i: Int, item: T) = mapIndexed { j, x -> if (j == i) item else x }

/** A 24-hour time picker; returns minutes after midnight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, minutes: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
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
    return raw(runs.joinToString(", ") { r ->
        when (r.last - r.first) {
            0 -> name(r.first)
            1 -> "${name(r.first)}, ${name(r.last)}"
            else -> "${name(r.first)}–${name(r.last)}"
        }
    })
}

/** The Settings row's summary of the schedule. */
fun scheduleText(c: RunConditions): UiText = when {
    !c.timeSchedule -> text(R.string.any_time)
    c.windows.size == 1 -> windowText(c.windows.single())
    else -> plural(R.plurals.schedule_and_more, c.windows.size - 1, windowText(c.windows.first()), c.windows.size - 1)
}
