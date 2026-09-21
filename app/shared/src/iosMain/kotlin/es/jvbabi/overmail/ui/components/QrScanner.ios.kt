package es.jvbabi.overmail.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIView
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/** AVFoundation does both the preview and the detection, no extra dependency needed. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrScanner(
    onQrCodeScanned: (String) -> Unit,
    modifier: Modifier,
) {
    val currentOnQrCodeScanned by rememberUpdatedState(onQrCodeScanned)
    val session = remember { AVCaptureSession() }
    // The output only holds its delegate weakly, so it has to live here.
    val delegate = remember { QrCodeDelegate { currentOnQrCodeScanned(it) } }

    DisposableEffect(session) {
        val input = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            ?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
        if (input != null && session.canAddInput(input)) session.addInput(input)

        val output = AVCaptureMetadataOutput()
        if (session.canAddOutput(output)) {
            session.addOutput(output)
            // Only valid once the output is part of the session.
            output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
            output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
        }

        // startRunning blocks until the camera is up, which must not happen on the main thread.
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            session.startRunning()
        }

        onDispose {
            session.stopRunning()
            session.inputs.forEach { session.removeInput(it as AVCaptureDeviceInput) }
            session.outputs.forEach { session.removeOutput(it as AVCaptureOutput) }
        }
    }

    UIKitView(
        factory = { CameraPreviewView(session) },
        modifier = modifier,
    )
}

private class QrCodeDelegate(
    private val onQrCodeScanned: (String) -> Unit,
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        didOutputMetadataObjects
            .firstNotNullOfOrNull { (it as? AVMetadataMachineReadableCodeObject)?.stringValue }
            ?.let(onQrCodeScanned)
    }
}

/** A preview layer does not follow its view's size by itself. */
@OptIn(ExperimentalForeignApi::class)
private class CameraPreviewView(session: AVCaptureSession) : UIView(frame = CGRectZero.readValue()) {
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    init {
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }
}
