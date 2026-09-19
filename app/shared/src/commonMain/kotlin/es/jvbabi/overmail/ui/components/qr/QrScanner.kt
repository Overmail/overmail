package es.jvbabi.overmail.ui.components.qr

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Camera preview that reports the content of every QR code it sees, repeatedly for as long as the
 * code stays in view. Expects the camera permission to be granted, see [CameraPermissionGate].
 */
@Composable
expect fun QrScanner(
    onScan: (String) -> Unit,
    modifier: Modifier = Modifier,
)
