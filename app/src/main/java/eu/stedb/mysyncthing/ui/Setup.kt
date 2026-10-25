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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.refresh()
        // A notification posted while denied stays hidden until it is posted again.
        if (granted) SyncthingService.refreshNotification(context)
    }
    return { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

@Composable
fun SetupScreen(state: PermissionState) {
    val requestNotifications = rememberNotificationRequest(state)
    // Surface sets the text colour for dark mode; there is no Scaffold here.
    Surface(Modifier.fillMaxSize(), color = AppColors.background) {
        Column(
            // Scrolls so the last button stays reachable in landscape.
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Set up", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Syncthing reads and writes your folders directly, so it needs All files access. " +
                    "Without it, nothing can sync."
            )
            Button(onClick = state::requestStorage) { Text("Grant All files access") }
            if (!state.notifications) {
                Text("Optional: notifications show sync status and new device requests.")
                OutlinedButton(onClick = requestNotifications) { Text("Allow notifications") }
            }
            if (!state.batteryExempt) {
                Text("Optional: exempting the app from battery optimisation keeps sync running reliably.")
                OutlinedButton(onClick = state::requestBatteryExemption) { Text("Disable battery optimisation") }
            }
        }
    }
}
