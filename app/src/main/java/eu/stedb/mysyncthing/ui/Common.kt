package eu.stedb.mysyncthing.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.stedb.mysyncthing.core.CoreState

/** Shows [content] only while the core runs; otherwise its state. */
@Composable
fun CoreGate(state: CoreState, content: @Composable () -> Unit) {
    when (state) {
        is CoreState.Running -> content()
        is CoreState.Failed -> Centered { Text("Syncthing failed: ${state.message}", color = MaterialTheme.colorScheme.error) }
        else -> Centered { CircularProgressIndicator(); Text("Starting Syncthing") }
    }
}

/** Shown instead of an empty list: what the list holds and how to fill it. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String) {
    Centered {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/**
 * Folder picker returning a POSIX path. With All files access the core uses
 * paths directly, so the SAF tree URI is only used to name the directory.
 */
@Composable
fun rememberFolderPicker(onPath: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::treeUriToPath)?.let(onPath)
    }
    return { launcher.launch(null) }
}

fun treeUriToPath(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = DocumentsContract.getTreeDocumentId(uri)
    val volume = docId.substringBefore(':')
    val rel = docId.substringAfter(':', "")
    val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
    return if (rel.isEmpty()) root else "$root/$rel"
}
