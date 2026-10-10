package com.mtajchert.carom.app.feature.onboarding.impl.scanner

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Back-camera preview that reports QR codes it reads. Needs the CAMERA permission. The camera is
 * bound to the composition and the lifecycle: it stops when the step is left or the app goes to
 * the background. [onCode] runs on the main thread, once per distinct text.
 */
@Composable
internal fun QrScanner(
    onCode: (String) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCode by rememberUpdatedState(onCode)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest = it } }
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(analysisExecutor, QrAnalyzer(ContextCompat.getMainExecutor(context)) { latestOnCode(it) })
            }
        try {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            awaitCancellation()
        } finally {
            provider.unbind(preview, analysis)
            analysis.clearAnalyzer()
            analysisExecutor.shutdown()
            surfaceRequest = null
        }
    }

    Box(modifier.semantics { this.contentDescription = contentDescription }) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it) }
    }
}

/** Decodes the Y plane of each frame; delivers each distinct text once on [callbackExecutor]. */
private class QrAnalyzer(
    private val callbackExecutor: Executor,
    private val onCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val decoder = QrDecoder()
    private var buffer = ByteArray(0)
    private var lastDelivered: String? = null

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val rowStride = plane.rowStride
            val bytes = plane.buffer
            bytes.rewind()
            val size = rowStride * it.height
            if (buffer.size != size) buffer = ByteArray(size)
            bytes.get(buffer, 0, minOf(bytes.remaining(), size))
            val text = decoder.decode(buffer, rowStride, it.width, it.height) ?: return
            if (text != lastDelivered) {
                lastDelivered = text
                callbackExecutor.execute { onCode(text) }
            }
        }
    }
}
