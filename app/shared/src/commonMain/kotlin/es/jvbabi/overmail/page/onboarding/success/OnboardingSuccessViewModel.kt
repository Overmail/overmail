package es.jvbabi.overmail.page.onboarding.success

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.repository.AccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.uuid.Uuid

/** Greets the account the sign-in step created, by the name it was given. */
class OnboardingSuccessViewModel(
    userId: Uuid,
    accountRepository: AccountRepository,
) : ViewModel() {
    val state: StateFlow<OnboardingSuccessState> = accountRepository.getById(userId)
        .map { account ->
            OnboardingSuccessState(
                // The server does not insist on a first name, the username is always there.
                name = account?.firstName?.takeIf { it.isNotBlank() } ?: account?.username,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingSuccessState())
}

data class OnboardingSuccessState(
    /** Null until the account has been read. */
    val name: String? = null,
)
