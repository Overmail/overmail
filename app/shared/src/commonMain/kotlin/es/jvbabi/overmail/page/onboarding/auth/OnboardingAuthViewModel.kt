package es.jvbabi.overmail.page.onboarding.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.PermissionsController
import dev.icerock.moko.permissions.RequestCanceledException
import dev.icerock.moko.permissions.camera.CAMERA
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.RedeemAuthCodeResponse
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class OnboardingAuthViewModel(
    private val permissionsController: PermissionsController,
    private val accountRepository: AccountRepository,
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
            is OnboardingAuthEvent.SubmitCode -> viewModelScope.launch {
                if (state.value.codeState is OnboardingAuthState.CodeState.Processing) return@launch

                state.update { it.copy(code = event.code, codeState = OnboardingAuthState.CodeState.Processing) }
                val regex = Regex("""^overmail://([^?]+)/auth\?code=(.+)$""")

                val match = regex.matchEntire(event.code)
                if (match == null) {
                    state.update { it.copy(codeState = OnboardingAuthState.CodeState.Error.BadFormat) }
                    return@launch
                }

                val encodedOrigin = match.groupValues[1]
                val homeserver = encodedOrigin.decodeURLQueryComponent()
                val authCode = match.groupValues[2]

                try {
                    withTimeout(10.seconds) {
                        val result = accountRepository.redeemAuthCode(
                            homeserver = homeserver,
                            code = authCode,
                        )

                        if (result.isFailure) {
                            state.update { it.copy(codeState = OnboardingAuthState.CodeState.Error.OtherError(result.exceptionOrNull()!!.message.orEmpty())) }
                            return@withTimeout
                        }

                        val resultData = result.getOrNull()!!
                        when (resultData) {
                            is RedeemAuthCodeResponse.CodeNotFound -> state.update { it.copy(codeState = OnboardingAuthState.CodeState.Error.NotExists) }
                            is RedeemAuthCodeResponse.Success -> state.update { it.copy(codeState = OnboardingAuthState.CodeState.Success) }
                        }
                    }
                } finally {
                    if (state.value.codeState is OnboardingAuthState.CodeState.Processing) state.update { it.copy(codeState = OnboardingAuthState.CodeState.Idle) }
                }
            }
        }
    }
}

data class OnboardingAuthState(
    val mode: Mode = Mode.Qr,
    val isCameraPermissionGranted: Boolean = false,
    val code: String? = null,
    val codeState: CodeState = CodeState.Idle,
) {
    enum class Mode {
        Qr, Code
    }

    sealed class CodeState {
        data object Idle : CodeState()
        data object Processing : CodeState()
        sealed class Error: CodeState() {
            data object BadFormat : Error()
            data object NotExists: Error()
            data class OtherError(val message: String) : Error()
        }
        data object Success : CodeState()
    }
}

sealed class OnboardingAuthEvent {
    data class SetMode(val mode: OnboardingAuthState.Mode) : OnboardingAuthEvent()
    data class SubmitCode(val code: String) : OnboardingAuthEvent()
}