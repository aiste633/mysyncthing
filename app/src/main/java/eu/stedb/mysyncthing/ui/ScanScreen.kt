package eu.stedb.mysyncthing.ui

import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.stedb.mysyncthing.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executors

/**
 * The camera, until it reads a QR code: [onResult] gets the code's text, [onBack] leaves
 * without one. Open it only with the camera permission granted. Pictures are searched for a
 * code and dropped: none is stored or sent.
 */
@Composable
fun ScanScreen(onResult: (String) -> Unit, onBack: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    ScanLayout(failed, onBack) { QrCamera(onResult, onFailed = { failed = it }, Modifier.fillMaxSize()) }
}

/** The scan screen around the camera's [picture]; [failed] says so over it, where the picture stays black. */
@Composable
fun ScanLayout(failed: Boolean, onBack: () -> Unit, picture: @Composable () -> Unit) {
    AppScreen(title = stringResource(R.string.scan_qr_code), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.Black), contentAlignment = Alignment.Center) {
                picture()
                if (failed) Text(stringResource(R.string.scan_failed), Modifier.padding(32.dp), color = Color.White, textAlign = TextAlign.Center)
                else CornerMarks()
            }
            Text(
                stringResource(R.string.scan_hint), Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where to hold the code. A guide only: the whole picture is read. */
@Composable
private fun CornerMarks() = Canvas(Modifier.fillMaxSize()) {
    val side = size.minDimension * 0.7f
    val arm = side / 8
    val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
    for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
        val corner = origin + Offset(if (right) side else 0f, if (bottom) side else 0f)
        drawLine(Color.White, corner, corner + Offset(if (right) -arm else arm, 0f), 3.dp.toPx())
        drawLine(Color.White, corner, corner + Offset(0f, if (bottom) -arm else arm), 3.dp.toPx())
    }
}

/** 640 × 480, CameraX's own choice for analysis, is too coarse for a code held at arm's length. */
private val SHARP = ResolutionSelector.Builder()
    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
    .build()

/**
 * The camera's picture, while the screen is in front. [onResult] gets the first QR code read,
 * once; [onFailed] whether the camera is out of order (none there, in use, switched off by a policy).
 */
@Composable
private fun QrCamera(onResult: (String) -> Unit, onFailed: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val result by rememberUpdatedState(onResult)
    val failed by rememberUpdatedState(onFailed)
    var picture by remember { mutableStateOf<SurfaceRequest?>(null) }
    LaunchedEffect(owner) {
        val found = CompletableDeferred<String>()
        val reader = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build().apply { setSurfaceProvider { picture = it } }
        // Pictures that arrive while one is being read are dropped (the default), so reading never lags behind.
        val analysis = ImageAnalysis.Builder().setResolutionSelector(SHARP).build()
            .apply { setAnalyzer(reader, QrAnalyzer { found.complete(it) }) }
        val state = Observer<CameraState> { failed(it.error != null) }
        var provider: ProcessCameraProvider? = null
        try {
            provider = ProcessCameraProvider.awaitInstance(context)
            val back = CameraSelector.DEFAULT_BACK_CAMERA
            val camera = provider.bindToLifecycle(owner, if (provider.hasCamera(back)) back else CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            camera.cameraInfo.cameraState.observe(owner, state)
            try {
                result(found.await())
            } finally {
                camera.cameraInfo.cameraState.removeObserver(state)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No camera at all, or CameraX could not start.
            failed(true)
        } finally {
            provider?.unbind(preview, analysis)
            reader.shutdown()
        }
    }
    picture?.let { CameraXViewfinder(it, modifier) }
}

/** Reads the brightness plane of each picture. One buffer for all: CameraX hands over one picture at a time. */
private class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    private var luma = ByteArray(0)

    override fun analyze(image: ImageProxy) = image.use {
        val plane = it.planes[0]
        val size = plane.buffer.remaining()
        if (luma.size < size) luma = ByteArray(size)
        plane.buffer.get(luma, 0, size)
        readQrCode(luma, it.width, it.height, plane.rowStride)?.let(onFound)
        Unit
    }
}
