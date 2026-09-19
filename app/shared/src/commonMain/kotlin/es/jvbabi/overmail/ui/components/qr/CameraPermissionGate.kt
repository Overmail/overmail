package es.jvbabi.overmail.ui.components.qr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.icerock.moko.permissions.DeniedAlwaysException
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.PermissionState
import dev.icerock.moko.permissions.RequestCanceledException
import dev.icerock.moko.permissions.camera.CAMERA
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.camera_permission_denied
import overmail.app.shared.generated.resources.camera_permission_denied_always
import overmail.app.shared.generated.resources.camera_permission_grant
import overmail.app.shared.generated.resources.camera_permission_open_settings

private enum class CameraPermissionState {
    Checking,
    Granted,

    /** Can be asked for again. */
    Denied,

    /** Only the system settings can grant it now. */
    DeniedAlways,
}

/**
 * Shows [content] once the camera permission is granted. Asks for it the first time it is shown,
 * afterwards only on the user's request.
 */
@Composable
fun CameraPermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val factory = rememberPermissionsControllerFactory()
    val controller = remember(factory) { factory.createPermissionsController() }
    BindEffect(controller)

    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CameraPermissionState.Checking) }
    var isRequesting by remember { mutableStateOf(false) }
    var hasRequested by rememberSaveable { mutableStateOf(false) }

    fun request() {
        hasRequested = true
        isRequesting = true
        scope.launch {
            state = try {
                controller.providePermission(Permission.CAMERA)
                CameraPermissionState.Granted
            } catch (_: DeniedAlwaysException) {
                CameraPermissionState.DeniedAlways
            } catch (_: DeniedException) {
                CameraPermissionState.Denied
            } catch (_: RequestCanceledException) {
                CameraPermissionState.Denied
            } finally {
                isRequesting = false
            }
        }
    }

    // Also runs when coming back from the system settings. Not cancelled on pause: the permission
    // dialog pauses the activity on Android.
    LifecycleResumeEffect(controller) {
        scope.launch {
            when {
                controller.isPermissionGranted(Permission.CAMERA) -> state = CameraPermissionState.Granted
                isRequesting -> Unit
                !hasRequested -> request()
                state == CameraPermissionState.Checking -> state =
                    // Android only knows after a request, iOS already before one.
                    if (controller.getPermissionState(Permission.CAMERA) == PermissionState.DeniedAlways) {
                        CameraPermissionState.DeniedAlways
                    } else {
                        CameraPermissionState.Denied
                    }
            }
        }
        onPauseOrDispose { }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (state) {
            CameraPermissionState.Checking -> CircularProgressIndicator()
            CameraPermissionState.Granted -> content()
            CameraPermissionState.Denied -> PermissionMissing(
                message = stringResource(Res.string.camera_permission_denied),
                action = stringResource(Res.string.camera_permission_grant),
                onAction = ::request,
            )

            CameraPermissionState.DeniedAlways -> PermissionMissing(
                message = stringResource(Res.string.camera_permission_denied_always),
                action = stringResource(Res.string.camera_permission_open_settings),
                onAction = controller::openAppSettings,
            )
        }
    }
}

@Composable
private fun PermissionMissing(
    message: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAction) {
            Text(action)
        }
    }
}
