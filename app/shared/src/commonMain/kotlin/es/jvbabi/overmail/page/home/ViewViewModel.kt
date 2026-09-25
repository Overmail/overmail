package es.jvbabi.overmail.page.home

import androidx.lifecycle.ViewModel
import es.jvbabi.overmail.domain.model.ViewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The view the listing shows -- filter, groupings, sorting. What goes into editing it is
 * [ViewSettingsViewModel]'s, which hands the result back through [ViewEvent.SetViewState].
 */
class ViewViewModel : ViewModel() {
    val viewState: StateFlow<ViewState>
        field = MutableStateFlow(ViewState.Mailbox)

    fun onEvent(event: ViewEvent) {
        when (event) {
            is ViewEvent.SetViewState -> viewState.value = event.viewState
        }
    }
}

sealed class ViewEvent {
    data class SetViewState(val viewState: ViewState) : ViewEvent()
}
