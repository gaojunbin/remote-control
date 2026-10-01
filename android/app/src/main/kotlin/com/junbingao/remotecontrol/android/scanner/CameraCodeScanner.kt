package com.junbingao.remotecontrol.android.scanner

import android.content.Context
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.junbingao.remotecontrol.android.attachments.CameraAccess
import com.junbingao.remotecontrol.android.attachments.CameraAccessRequest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The phone's camera reading QR codes: the counterpart of `CameraCodeScanner`
 * (`docs/DESIGN.md` § "The Android app": CameraX and the on-device ML Kit scanner, so no Play
 * services are needed). The model ships in the app and nothing leaves the phone.
 *
 * The camera is bound to the screen's lifecycle, so it stops when the app leaves the front and
 * starts again when it returns, and it is unbound when the viewfinder leaves the screen — the
 * iPhone found a preview that never restarted reads exactly like a camera that is looking.
 */
class CameraCodeScanner(private val access: CameraAccessRequest) : CodeScanning {
    override suspend fun requestAccess(): CameraAccess = access.request()

    @Composable
    override fun Viewfinder(onCode: (String) -> Unit, modifier: Modifier) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current
        val report = rememberUpdatedState(onCode)
        val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
        DisposableEffect(lifecycle) {
            val session = ScanSession(context, lifecycle, preview) { payload -> report.value(payload) }
            session.start()
            onDispose { session.stop() }
        }
        AndroidView(factory = { preview }, modifier = modifier)
    }
}

/** One camera binding and one detector, from the viewfinder's arrival to its departure. */
private class ScanSession(
    private val context: Context,
    private val lifecycle: LifecycleOwner,
    private val preview: PreviewView,
    private val onCode: (String) -> Unit,
) {
    private val detector: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    private val analysis: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = ContextCompat.getMainExecutor(context)
    private val dedupe = SamePayload()
    private var provider: ProcessCameraProvider? = null
    private var stopped = false

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            val cameras = future.get()
            provider = cameras
            val shown = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val reading = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysis, ::read) }
            cameras.unbindAll()
            cameras.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, shown, reading)
        }, main)
    }

    fun stop() {
        stopped = true
        provider?.unbindAll()
        provider = null
        detector.close()
        analysis.shutdown()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun read(frame: ImageProxy) {
        val image = frame.image
        if (image == null || stopped) {
            frame.close()
            return
        }
        detector.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
            .addOnSuccessListener(main) { codes ->
                codes.firstNotNullOfOrNull { it.rawValue }?.let { payload ->
                    if (!stopped && dedupe.isNew(payload)) onCode(payload)
                }
            }
            .addOnCompleteListener { frame.close() }
    }
}

/**
 * The scanner reports the same code on every frame it stays in view; it is handed on once, and
 * again only after a different one was seen, as the iPhone's coordinator does.
 */
internal class SamePayload {
    private var last: String? = null

    fun isNew(payload: String): Boolean {
        if (payload == last) return false
        last = payload
        return true
    }
}
