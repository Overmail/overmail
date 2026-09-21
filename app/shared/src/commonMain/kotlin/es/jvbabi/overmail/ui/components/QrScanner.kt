package es.jvbabi.overmail.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A live camera preview that reports the content of every QR code it sees, once per frame it is
 * seen in — deduplicating is up to the caller.
 *
 * Expects the camera permission to be granted already; it does not ask for it.
 */
@Composable
expect fun QrScanner(
    onQrCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
)
