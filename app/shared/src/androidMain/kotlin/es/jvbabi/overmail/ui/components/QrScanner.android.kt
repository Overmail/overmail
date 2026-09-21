package es.jvbabi.overmail.ui.components

import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors

/**
 * CameraX for the preview and the frames, ML Kit to find the code in them, after
 * https://github.com/aslansari/jetpack-compose-qr-scanner.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
actual fun QrScanner(
    onQrCodeScanned: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQrCodeScanned by rememberUpdatedState(onQrCodeScanned)
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(lifecycleOwner) {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
        val analysisExecutor = Executors.newSingleThreadExecutor()

        val preview = Preview.Builder().build()
        preview.surfaceProvider = previewView.surfaceProvider

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            val image = imageProxy.image ?: return@setAnalyzer imageProxy.close()
            scanner.process(InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees))
                // Delivered on the main thread, so the callback may touch compose state.
                .addOnSuccessListener { barcodes ->
                    barcodes.firstNotNullOfOrNull { it.rawValue }?.let(currentOnQrCodeScanned)
                }
                .addOnCompleteListener { imageProxy.close() }
        }

        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        try {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
            )
            awaitCancellation()
        } finally {
            cameraProvider.unbind(preview, imageAnalysis)
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}
