package eu.stedb.mysyncthing.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.CoreMemoryLimit
import eu.stedb.mysyncthing.core.SyncthingCore
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.joined
import eu.stedb.mysyncthing.core.processAnonSwapBytes
import eu.stedb.mysyncthing.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/** The phone as the memory limit sees it: Android's version and its memory in bytes. */
data class MemoryPhone(val sdk: Int, val totalBytes: Long) {
    val automaticMb get() = CoreMemoryLimit.automaticMb(sdk, totalBytes)
    val androidLimitMb get() = CoreMemoryLimit.androidLimitMb(sdk, totalBytes)

    companion object {
        fun of(totalBytes: Long) = MemoryPhone(Build.VERSION.SDK_INT, totalBytes)
    }
}

/** The stored choice: the user's own limit in MB, or null for the automatic one. */
data class OwnMemoryLimit(val mb: Long?)

/** [mb] megabytes, or "no limit" for 0. */
fun memoryLimitText(mb: Long): UiText = if (mb == 0L) text(R.string.memory_limit_none) else megabytes(mb)

/** The settings row's value: the user's [own] limit, or the automatic one with its value. */
fun memoryLimitRowText(own: Long?, phone: MemoryPhone): UiText = when {
    own != null -> memoryLimitText(own)
    phone.automaticMb == 0L -> text(R.string.memory_limit_automatic_none)
    else -> text(R.string.memory_limit_automatic, megabytes(phone.automaticMb))
}

private fun megabytes(mb: Long): UiText = withUnit(numberText(mb.toDouble()), "MB")

private fun bytesAsMb(bytes: Long): UiText = megabytes((bytes / 1e6).roundToLong())

/** Memory in use, in bytes: the core's Go runtime and the whole app as Android's limit counts it; null when unknown. */
data class MemoryUse(val core: Long?, val app: Long?)

/** Reads the memory in use now (not on the main thread). */
fun readMemoryUse() = MemoryUse(runCatching { SyncthingCore.memoryBytes() }.getOrNull(), processAnonSwapBytes())

/** How often the dialog reads the memory in use. */
const val MEMORY_REFRESH_S = 5

/**
 * The core memory limit: automatic (ticked, the field shows its value), or the user's own in MB, 0 for none.
 * Below, the memory in use now, read every [MEMORY_REFRESH_S] s while the dialog is open.
 * [onSave] gets null for automatic.
 */
@Composable
fun MemoryLimitDialog(
    own: Long?,
    phone: MemoryPhone,
    onDismiss: () -> Unit,
    read: () -> MemoryUse = ::readMemoryUse,
    onSave: (Long?) -> Unit,
) {
    var auto by rememberSaveable { mutableStateOf(own == null) }
    var value by rememberSaveable { mutableStateOf((own ?: phone.automaticMb).toString()) }
    val error = if (auto) null else memoryLimitError(value, phone.totalBytes)
    EntryDialog(
        stringResource(R.string.memory_limit), stringResource(R.string.action_save), error == null,
        onConfirm = { onSave(if (auto) null else wholeNumber(value)?.toLong()) }, onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().toggleable(auto, role = Role.Checkbox) {
                    auto = it
                    if (it) value = phone.automaticMb.toString()
                },
            ) {
                // Beside the name, not the middle of a hint that wraps to several lines at large fonts.
                val firstLine = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
                Box(Modifier.heightIn(min = firstLine), contentAlignment = Alignment.Center) { Checkbox(auto, onCheckedChange = null) }
                Column(Modifier.weight(1f)) {
                    Text(memoryLimitRowText(null, phone).asString(), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        (if (phone.androidLimitMb == null) text(R.string.memory_limit_automatic_off_hint)
                        else text(R.string.memory_limit_automatic_hint, megabytes(CoreMemoryLimit.MARGIN_MB))).asString(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextInput(
                value, { value = it }, Limits.MEMORY_LIMIT, Modifier.fillMaxWidth().inViewWhenFocused(),
                label = stringResource(R.string.limit_label), error = error, enabled = !auto,
                trailingIcon = { Text("MB", Modifier.padding(end = 16.dp)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(
                stringResource(R.string.memory_limit_hint, megabytes(CoreMemoryLimit.MIN_MB).asString()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            MemoryInUse(phone, read)
        }
    }
}

/** The memory in use now: the core's (what its limit counts) and the whole app's (what Android's limit counts). */
@Composable
private fun MemoryInUse(phone: MemoryPhone, read: () -> MemoryUse) {
    var use by remember { mutableStateOf(MemoryUse(null, null)) }
    LaunchedEffect(Unit) {
        while (true) {
            use = withContext(Dispatchers.IO) { read() }
            delay(MEMORY_REFRESH_S * 1000L)
        }
    }
    val (core, app) = use
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Dashes until the first read, so the rows keep their place.
        UsageRow(stringResource(R.string.memory_core_now), core?.let { bytesAsMb(it).asString() } ?: "– MB")
        UsageRow(stringResource(R.string.memory_app_now), app?.let { bytesAsMb(it).asString() } ?: "– MB")
        val android = phone.androidLimitMb?.let { text(R.string.memory_android_limit, megabytes(it)) } ?: text(R.string.memory_android_none)
        Text(
            listOf(android, text(R.string.memory_refresh, MEMORY_REFRESH_S)).joined(" · ").asString(),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}
