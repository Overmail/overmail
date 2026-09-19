package es.jvbabi.overmail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.repository.OvermailAccountRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppViewModel(
    private val accountRepository: OvermailAccountRepository,
) : ViewModel() {

    var state by mutableStateOf(AppState.Loading)
        private set

    init {
        // Follows the accounts, so adding the first one leaves the onboarding and removing the
        // last one returns to it.
        viewModelScope.launch {
            accountRepository.getAll()
                .map { it.isEmpty() }
                .distinctUntilChanged()
                .collect { hasNoAccounts ->
                    state = if (hasNoAccounts) AppState.Onboarding else AppState.Main
                }
        }
    }
}

enum class AppState {
    Loading,
    Onboarding,
    Main,
}
