package es.jvbabi.overmail.page.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.usecase.account.GetCurrentAccountUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class HomeViewModel(
    private val getCurrentAccount: GetCurrentAccountUseCase,
): ViewModel() {
    val state: StateFlow<HomeState>
        field = MutableStateFlow(HomeState())

    init {
        viewModelScope.launch {
            getCurrentAccount().collectLatest { currentUser ->
                state.update { it.copy(currentUser = currentUser) }
            }
        }

        // Read again rather than once at setup: the screen is left open, and 18:00 must not find
        // it still saying "Guten Tag". The time zone too, it may change while the app is open.
        viewModelScope.launch {
            while (isActive) {
                state.update { it.copy(now = localNow()) }
                delay(1.seconds)
            }
        }
    }
}

private fun localNow() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

data class HomeState(
    val currentUser: OvermailAccount? = null,
    /** The time on the device's clock, updated every second. */
    val now: LocalDateTime = localNow(),
) {
    val greeting: Greeting get() = Greeting.at(now.time)
}
