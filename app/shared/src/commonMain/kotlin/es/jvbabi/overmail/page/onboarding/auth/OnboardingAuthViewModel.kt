package es.jvbabi.overmail.page.onboarding.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.PermissionsController
import dev.icerock.moko.permissions.RequestCanceledException
import dev.icerock.moko.permissions.camera.CAMERA
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingAuthViewModel(
    private val permissionsController: PermissionsController,
): ViewModel() {
    val state: StateFlow<OnboardingAuthState>
        field = MutableStateFlow(OnboardingAuthState())

    fun onScreenUp() {
        viewModelScope.launch {
            val isGranted = try {
                permissionsController.providePermission(Permission.CAMERA)
                true
            } catch (_: DeniedException) {
                false
            } catch (_: RequestCanceledException) {
                false
            }
            state.update { it.copy(isCameraPermissionGranted = isGranted) }
        }
    }

    fun onEvent(event: OnboardingAuthEvent) {
        when (event) {
            is OnboardingAuthEvent.SetMode -> state.update { it.copy(mode = event.mode) }
            // TODO(#58): sign in with the code. Arrives once per frame while a QR is in view.
            is OnboardingAuthEvent.SubmitCode -> Unit
        }
    }
}

data class OnboardingAuthState(
    val mode: Mode = Mode.Qr,
    val isCameraPermissionGranted: Boolean = false,
) {
    enum class Mode {
        Qr, Code
    }
}

sealed class OnboardingAuthEvent {
    data class SetMode(val mode: OnboardingAuthState.Mode) : OnboardingAuthEvent()
    data class SubmitCode(val code: String) : OnboardingAuthEvent()
}