package es.jvbabi.overmail.page.home.components.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.Label
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.LabelsRepository
import es.jvbabi.overmail.domain.usecase.account.GetCurrentAccountUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val labelsRepository: LabelsRepository,
    private val getCurrentAccountUseCase: GetCurrentAccountUseCase,
) : ViewModel() {
    val state: StateFlow<SearchState>
        field = MutableStateFlow(SearchState())

    init {
        viewModelScope.launch {
            getCurrentAccountUseCase().collectLatest { account ->
                state.update { it.copy(overmailAccount = account) }
            }
        }

        viewModelScope.launch {
            state
                .filter { it.overmailAccount != null }
                .distinctUntilChanged { old, new ->
                    if (old.query != new.query) return@distinctUntilChanged false
                    if (old.overmailAccount?.id != new.overmailAccount?.id) return@distinctUntilChanged false

                    return@distinctUntilChanged true
                }
                .collectLatest { tempState ->
                    val query = tempState.query
                    val account = tempState.overmailAccount!!
                    labelsRepository.search(query, true, account).collectLatest { labels ->
                        state.update { it.copy(allSuggestedLabels = labels.data) }
                    }
                }
        }
    }

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.SetQuery -> {
                state.update { it.copy(query = event.query) }
            }

            is SearchEvent.AddLavel -> {
                state.update { it.copy(activeLavels = (it.activeLavels + event.label).distinctBy { it.id }) }
            }

            is SearchEvent.RemoveLavel -> {
                state.update { it.copy(activeLavels = it.activeLavels - event.label ) }
            }
        }
    }
}

data class SearchState(
    val query: String = "",
    val overmailAccount: OvermailAccount? = null,

    val allSuggestedLabels: List<Label> = emptyList(),
    val activeLavels: List<Label> = emptyList(),
) {
    val suggestedLabels: List<Label> = allSuggestedLabels.filter { it.id !in activeLavels.map { it.id } }
}

sealed class SearchEvent {
    data class SetQuery(val query: String) : SearchEvent()
    data class AddLavel(val label: Label) : SearchEvent()
    data class RemoveLavel(val label: Label) : SearchEvent()
}