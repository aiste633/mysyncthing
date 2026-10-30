package eu.stedb.mysyncthing.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.SyncthingService
import eu.stedb.mysyncthing.core.R as CoreR

/** Permissions the app depends on, re-read whenever the activity resumes. */
@Stable
class PermissionState(private val context: Context) {
    var storage by mutableStateOf(false); private set
    var notifications by mutableStateOf(false); private set
    var batteryExempt by mutableStateOf(false); private set

    fun refresh() {
        storage = Environment.isExternalStorageManager()
        notifications = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestStorage() = context.startActivity(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    // F-Droid build: direct exemption request is acceptable for a sync daemon.
    @SuppressLint("BatteryLife")
    fun requestBatteryExemption() = context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun rememberPermissionState(): PermissionState {
    val context = LocalContext.current
    val state = remember { PermissionState(context.applicationContext).also { it.refresh() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }
    return state
}

@Composable
fun rememberNotificationRequest(state: PermissionState): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun rationale() = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
    var rationaleBefore by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.refresh()
        // A notification posted while denied stays hidden until it is posted again.
        if (granted) SyncthingService.refreshNotification(context)
        // No rationale before or after a refusal: Android did not show its prompt,
        // because it was refused twice before. Only the app's notification settings can grant it now.
        else if (!rationaleBefore && !rationale()) context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    return {
        rationaleBefore = rationale()
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * First-run checklist. It stays until Done, so the optional items are offered
 * even after the required one is granted; Settings shows the same rows.
 */
@Composable
fun SetupScreen(state: PermissionState, mobileData: Boolean, onMobileData: (Boolean) -> Unit, onDone: () -> Unit) {
    val requestNotifications = rememberNotificationRequest(state)
    // Surface sets the text colour for dark mode; there is no Scaffold here.
    Surface(Modifier.fillMaxSize(), color = AppColors.background) {
        Column(
            // Scrolls so Done stays reachable in landscape.
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.setup_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(colors = AppColors.card(), border = AppColors.cardBorder()) {
                PermissionRows(state, requestNotifications, checklist = true)
            }
            Card(colors = AppColors.card(), border = AppColors.cardBorder()) {
                SwitchRow(
                    stringResource(R.string.setup_mobile_data),
                    stringResource(if (mobileData) R.string.setup_mobile_data_on else R.string.setup_mobile_data_off),
                    mobileData, Icons.Default.SignalCellularAlt, onMobileData,
                )
            }
            Button(onClick = onDone, enabled = state.storage, shape = ButtonShape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_done)) }
        }
    }
}

/** The three permission rows; the checklist adds tags and dividers, Settings matches its other groups. */
@Composable
fun PermissionRows(state: PermissionState, requestNotifications: () -> Unit, checklist: Boolean = false) {
    PermissionRow(
        Icons.Default.Folder, stringResource(R.string.permission_files), stringResource(R.string.permission_required).takeIf { checklist },
        stringResource(R.string.permission_files_hint),
        state.storage, stringResource(R.string.permission_grant), state::requestStorage,
    )
    if (checklist) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    PermissionRow(
        Icons.Default.Notifications, stringResource(R.string.permission_notifications), stringResource(R.string.permission_optional).takeIf { checklist },
        stringResource(R.string.permission_notifications_hint, stringResource(CoreR.string.channel_service)),
        state.notifications, stringResource(R.string.permission_allow), requestNotifications,
    )
    if (checklist) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    PermissionRow(
        Icons.Default.BatteryFull, stringResource(R.string.permission_background), stringResource(R.string.permission_recommended).takeIf { checklist },
        stringResource(R.string.permission_background_hint),
        state.batteryExempt, stringResource(R.string.permission_allow), state::requestBatteryExemption,
    )
    BrandBatteryWarning()
}

private const val DONT_KILL = "https://dontkillmyapp.com"

/**
 * Android's exemption is not all of it: many brands add their own battery savers
 * that still stop the app, granted or not. Opens the per-brand steps in the browser.
 */
@Composable
private fun BrandBatteryWarning() {
    val uri = LocalUriHandler.current
    val error = MaterialTheme.colorScheme.error
    val site = DONT_KILL.removePrefix("https://")
    val text = stringResource(R.string.permission_background_brands, site)
    Row(
        // Below the row's text: past the 16 dp edge, the 24 dp icon and the 16 dp gap of a ListItem.
        Modifier.fillMaxWidth().clickable { uri.openUri(DONT_KILL) }.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Warning, null, Modifier.size(18.dp).padding(top = 1.dp), tint = error)
        // The open-link icon right after the address, at text size: off to the side it read as
        // part of Granted / Allow.
        val at = text.lastIndexOf(site)
        // What TalkBack reads for the icon; Compose refuses an empty one.
        val opens = stringResource(R.string.link_opens_browser)
        Text(
            buildAnnotatedString {
                if (at < 0) {
                    append(text)
                } else {
                    append(text, 0, at)
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(site) }
                    append('\u00A0')
                    appendInlineContent(LINK_ICON, opens)
                    append(text, at + site.length, text.length)
                }
            },
            inlineContent = mapOf(
                LINK_ICON to InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.fillMaxSize(), tint = error)
                },
            ),
            style = MaterialTheme.typography.bodyMedium, color = error, modifier = Modifier.weight(1f),
        )
    }
}

private const val LINK_ICON = "link"

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    tag: String?,
    description: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        // Above the title, so a long tag never wraps beside it on a narrow phone.
        overlineContent = tag?.let { { Text(it) } },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = AppColors.ok)
                    Text(stringResource(R.string.permission_granted), color = AppColors.ok, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                OutlinedButton(onClick = onAction, shape = ButtonShape) { Text(action) }
            }
        },
    )
}
