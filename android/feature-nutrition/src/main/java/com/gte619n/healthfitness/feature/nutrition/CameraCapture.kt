package com.gte619n.healthfitness.feature.nutrition

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * A CameraX preview bound to the composable's lifecycle. Optionally runs an
 * ML Kit barcode analyzer (when [onBarcode] is non-null) and exposes a still
 * capture via [controller]. Mirrors the standard CameraX + Compose AndroidView
 * pattern.
 */
@Composable
fun CameraPreview(
    controller: CameraCaptureController,
    onBarcode: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    AndroidView(
        modifier = modifier,
        factory = { previewView },
        update = {
            controller.bind(
                context = context,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                onBarcode = onBarcode,
            )
        },
    )
}

/**
 * Holds the CameraX use cases so the composable can trigger a still capture.
 * One instance per Capture screen (remembered). Not a Hilt type — it owns
 * Android camera resources tied to the composition.
 */
class CameraCaptureController {

    private var imageCapture: ImageCapture? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner = BarcodeScanning.getClient()
    private var lastEmittedBarcode: String? = null

    fun bind(
        context: Context,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        previewView: PreviewView,
        onBarcode: ((String) -> Unit)?,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture

            val useCases = mutableListOf(preview, capture)

            if (onBarcode != null) {
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    processBarcode(proxy, onBarcode)
                }
                useCases += analysis
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases.toTypedArray(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processBarcode(proxy: ImageProxy, onBarcode: (String) -> Unit) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { codes ->
                val value = codes.firstOrNull { it.rawValue != null }?.rawValue
                if (value != null && value != lastEmittedBarcode) {
                    lastEmittedBarcode = value
                    onBarcode(value)
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Reset so the same barcode can be re-emitted after a reset. */
    fun clearBarcodeMemo() { lastEmittedBarcode = null }

    /** Take a still photo and deliver compressed JPEG bytes. */
    fun takePhoto(context: Context, onResult: (ByteArray) -> Unit, onError: (Throwable) -> Unit) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera not ready"))
            return
        }
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        onResult(image.toJpegBytes())
                    } catch (e: Exception) {
                        onError(e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            },
        )
    }

    companion object {
        private const val TAG = "NutritionCamera"
    }
}

/** JPEG ImageProxy → ByteArray (CameraX delivers JPEG for captured stills). */
private fun ImageProxy.toJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

@Suppress("unused")
private fun ByteArrayOutputStream.bytesOrEmpty(): ByteArray = toByteArray()
