package eu.stedb.mysyncthing.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SignalCellularAlt
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import eu.stedb.mysyncthing.core.SyncthingService

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
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    // F-Droid build: direct exemption request is acceptable for a sync daemon.
    @SuppressLint("BatteryLife")
    fun requestBatteryExemption() = context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
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
            Text("Set up", style = MaterialTheme.typography.headlineMedium)
            Text(
                "MySyncthing needs one permission to sync. The others make it more reliable.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(colors = AppColors.card(), border = AppColors.cardBorder()) {
                PermissionRows(state, requestNotifications, checklist = true)
            }
            Card(colors = AppColors.card(), border = AppColors.cardBorder()) {
                SwitchRow(
                    "Sync on mobile data", if (mobileData) "On: also uses mobile data, not while roaming" else "Off: only on Wi-Fi or Ethernet. You can change it in Settings.",
                    mobileData, Icons.Default.SignalCellularAlt, onMobileData,
                )
            }
            Button(onClick = onDone, enabled = state.storage, shape = ButtonShape, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

/** The three permission rows; the checklist adds tags and dividers, Settings matches its other groups. */
@Composable
fun PermissionRows(state: PermissionState, requestNotifications: () -> Unit, checklist: Boolean = false) {
    PermissionRow(
        Icons.Default.Folder, "All files access", "Required".takeIf { checklist },
        "Syncthing reads and writes your folders directly.",
        state.storage, "Grant", state::requestStorage,
    )
    if (checklist) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    PermissionRow(
        Icons.Default.Notifications, "Notifications", "Optional".takeIf { checklist },
        "Requests from new devices and folders, and problems. While Syncthing runs, Android also requires a small status notification; " +
            "to hide it, turn off its \u201cSync service\u201d category.",
        state.notifications, "Allow", requestNotifications,
    )
    if (checklist) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    PermissionRow(
        Icons.Default.BatteryFull, "Run in background", "Recommended".takeIf { checklist },
        "Keeps syncing with the screen off. Some phones call this \u201cAllow background activity\u201d.",
        state.batteryExempt, "Allow", state::requestBatteryExemption,
    )
}

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
                    Text("Granted", color = AppColors.ok, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                OutlinedButton(onClick = onAction, shape = ButtonShape) { Text(action) }
            }
        },
    )
}
