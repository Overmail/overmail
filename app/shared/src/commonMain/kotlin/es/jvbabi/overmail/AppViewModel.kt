package es.jvbabi.overmail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.usecase.housekeeping.SetupApplicationUseCase
import kotlinx.coroutines.launch

/**
 * Belongs to the activity (the root view controller on iOS), so the setup starts once and is not
 * restarted by a recomposition or a configuration change.
 */
class AppViewModel(
    setupApplication: SetupApplicationUseCase,
) : ViewModel() {
    init {
        viewModelScope.launch { setupApplication() }
    }
}
