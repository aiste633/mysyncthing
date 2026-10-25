package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.SyncState

/** Wallpaper colours (Material You); minSdk 35 always has them. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Screens sit on a tinted background and lists are cards with an outline, so
 * items stay apart in both themes instead of blending into one white sheet.
 */
object AppColors {
    // Light: white cards on a tinted grey. Dark: lifted cards on the darkest surface.
    val background: Color @Composable @ReadOnlyComposable get() =
        if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer

    @Composable
    fun card(): CardColors = CardDefaults.cardColors(
        containerColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLowest,
    )

    @Composable
    fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

    /** Material has no success/warning roles; these keep 4.5:1 contrast on the card colours. */
    val ok: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF81C995) else Color(0xFF1E7B34)
    val busy: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFFFDD663) else Color(0xFF8A5A00)
    val problem: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
    val neutral: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

/** Colour and icon for each overall state; the icon carries the meaning for colour-blind users too. */
data class StatusLook(val icon: ImageVector, val color: Color)

@Composable
fun SyncState.look(): StatusLook = when (this) {
    SyncState.UpToDate -> StatusLook(Icons.Default.CheckCircle, AppColors.ok)
    SyncState.Syncing -> StatusLook(Icons.Default.Sync, AppColors.busy)
    SyncState.Paused -> StatusLook(Icons.Default.PauseCircle, AppColors.busy)
    SyncState.NeedsInput -> StatusLook(Icons.Default.NotificationsActive, AppColors.busy)
    SyncState.Problem -> StatusLook(Icons.Default.Error, AppColors.problem)
    SyncState.Stopped -> StatusLook(Icons.Default.StopCircle, AppColors.neutral)
}
