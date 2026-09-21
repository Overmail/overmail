package es.jvbabi.overmail.page.onboarding

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/**
 * What the onboarding has collected so far, for the steps that come after the one that found it
 * out; the steps' own view models keep only what their screen needs.
 *
 * No navigation entry has a view model store of its own, so this one belongs to the activity (the
 * root view controller on iOS): it outlives [OnboardingRoot] and lasts as long as the process.
 */
class OnboardingViewModel : ViewModel() {
    val state: StateFlow<OnboardingState>
        field = MutableStateFlow(OnboardingState())

    fun onUserCreated(userId: Uuid) {
        state.update { it.copy(userId = userId) }
    }
}

data class OnboardingState(
    /** The account the sign-in step created, once it has. */
    val userId: Uuid? = null,
)
