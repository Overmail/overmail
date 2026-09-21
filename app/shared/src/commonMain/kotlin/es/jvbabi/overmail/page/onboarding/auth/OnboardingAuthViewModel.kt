package es.jvbabi.overmail.page.onboarding.auth

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class OnboardingAuthViewModel: ViewModel() {
    val state: StateFlow<OnboardingAuthState>
        field = MutableStateFlow(OnboardingAuthState())

    fun onEvent(event: OnboardingAuthEvent) {
        when (event) {
            is OnboardingAuthEvent.SetMode -> state.update { it.copy(mode = event.mode) }
        }
    }
}

data class OnboardingAuthState(
    val mode: Mode = Mode.Qr
) {
    enum class Mode {
        Qr, Code
    }
}

sealed class OnboardingAuthEvent {
    data class SetMode(val mode: OnboardingAuthState.Mode) : OnboardingAuthEvent()
}