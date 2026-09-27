package es.jvbabi.overmail.domain.usecase.housekeeping

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class SetupApplicationUseCase(
    private val keepCurrentAccountValid: KeepCurrentAccountValidUseCase,
) {
    /**
     * Starts everything that keeps the app's state in order while it runs. Each task runs for as
     * long as the app does, so they are launched side by side and this suspends until cancelled.
     */
    suspend operator fun invoke() = coroutineScope {
        launch { keepCurrentAccountValid() }
    }
}
