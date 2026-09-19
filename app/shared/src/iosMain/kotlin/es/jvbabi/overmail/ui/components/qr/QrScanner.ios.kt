@file:OptIn(ExperimentalForeignApi::class)

package es.jvbabi.overmail.ui.components.qr

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

/** ML Kit needs CocoaPods on iOS, AVFoundation detects QR codes on its own. */
@Composable
actual fun QrScanner(
    onScan: (String) -> Unit,
    modifier: Modifier,
) {
    val currentOnScan by rememberUpdatedState(onScan)
    val scanner = remember { CaptureQrScanner { currentOnScan(it) } }

    DisposableEffect(scanner) {
        scanner.start()
        onDispose { scanner.stop() }
    }

    UIKitView(
        factory = { CapturePreviewView(scanner.session) },
        modifier = modifier,
    )
}

private class CaptureQrScanner(onScan: (String) -> Unit) {
    val session = AVCaptureSession()

    // Held here, the output only keeps a weak reference to it.
    private val delegate = object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
        override fun captureOutput(
            output: AVCaptureOutput,
            didOutputMetadataObjects: List<*>,
            fromConnection: AVCaptureConnection,
        ) {
            didOutputMetadataObjects
                .filterIsInstance<AVMetadataMachineReadableCodeObject>()
                .firstNotNullOfOrNull { it.stringValue }
                ?.let(onScan)
        }
    }

    init {
        // Null on the simulator, which has no camera: the preview just stays black.
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
        if (input != null && session.canAddInput(input)) session.addInput(input)

        val output = AVCaptureMetadataOutput()
        if (session.canAddOutput(output)) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
            // Only available types may be set, and those are only known once the output is added.
            output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        }
    }

    // Both block until the session has changed, which must not happen on the main thread.
    fun start() = onBackground { session.startRunning() }
    fun stop() = onBackground { session.stopRunning() }

    private fun onBackground(block: () -> Unit) {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u), block)
    }
}

/** Keeps the preview layer filling the view, which a plain sublayer does not do on its own. */
private class CapturePreviewView(session: AVCaptureSession) : UIView(frame = CGRectZero.readValue()) {
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
