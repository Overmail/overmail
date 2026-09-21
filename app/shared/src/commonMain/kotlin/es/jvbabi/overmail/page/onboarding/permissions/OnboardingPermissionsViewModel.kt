package es.jvbabi.overmail.page.onboarding.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.PermissionsController
import dev.icerock.moko.permissions.RequestCanceledException
import dev.icerock.moko.permissions.notifications.REMOTE_NOTIFICATION
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Asks whether Overmail may send notifications.
 *
 * The answer does not decide whether onboarding goes on: the app works without them, so the step
 * is over once the question has been asked, whatever the answer.
 */
class OnboardingPermissionsViewModel(
    private val permissionsController: PermissionsController,
) : ViewModel() {
    val state: StateFlow<OnboardingPermissionsState>
        field = MutableStateFlow(OnboardingPermissionsState())

    init {
        viewModelScope.launch {
            if (permissionsController.isPermissionGranted(Permission.REMOTE_NOTIFICATION)) {
                state.update { it.copy(isDone = true) }
            }
        }
    }

    fun onEvent(event: OnboardingPermissionsEvent) {
        when (event) {
            is OnboardingPermissionsEvent.Request -> viewModelScope.launch {
                try {
                    permissionsController.providePermission(Permission.REMOTE_NOTIFICATION)
                } catch (_: DeniedException) {
                    // Denied for good included: that one is a DeniedException, too.
                } catch (_: RequestCanceledException) {
                }
                state.update { it.copy(isDone = true) }
            }
        }
    }
}

data class OnboardingPermissionsState(
    /** Asked, or nothing left to ask: the step is over either way. */
    val isDone: Boolean = false,
)

sealed class OnboardingPermissionsEvent {
    data object Request : OnboardingPermissionsEvent()
}
