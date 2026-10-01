package es.jvbabi.overmail.page.home.components.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.Label
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.repository.LabelsRepository
import es.jvbabi.overmail.domain.repository.ParticipantsRepository
import es.jvbabi.overmail.domain.usecase.account.GetCurrentAccountUseCase
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class SearchViewModel(
    private val labelsRepository: LabelsRepository,
    private val participantsRepository: ParticipantsRepository,
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
                    coroutineScope {
                        launch {
                            labelsRepository.search(query, true, account).collectLatest { labels ->
                                state.update { it.copy(suggestedLabels = labels.data) }
                            }
                        }
                        launch {
                            participantsRepository.search(query.trim(), true, account).collectLatest { participants ->
                                state.update { it.copy(suggestedParticipants = participants.data) }
                            }
                        }
                    }
                }
        }
    }

    fun onEvent(event: SearchEvent) {
        when (event) {
            // Everything but the account, which is not the search's to forget.
            SearchEvent.Reset -> state.update { SearchState(overmailAccount = it.overmailAccount, suggestedLabels = it.suggestedLabels, suggestedParticipants = it.suggestedParticipants) }

            is SearchEvent.SetQuery -> {
                state.update { it.copy(query = event.query) }
            }

            is SearchEvent.ToggleLabel -> state.update { state ->
                val isActive = state.activeLabels.any { it.id == event.label.id }
                // What was typed has done its job once its label is picked, as in the label filter.
                if (isActive) state.copy(activeLabels = state.activeLabels.filterNot { it.id == event.label.id })
                else state.copy(activeLabels = state.activeLabels + event.label, query = "")
            }

            is SearchEvent.RemoveLabel -> state.update { state ->
                state.copy(activeLabels = state.activeLabels.filterNot { it.id == event.label.id })
            }

            is SearchEvent.ToggleParticipant -> state.update { state ->
                val active = state.activeParticipants(event.direction)
                val isActive = active.any { it.id == event.participant.id }
                if (isActive) state.withParticipants(event.direction, active.filterNot { it.id == event.participant.id })
                else state.withParticipants(event.direction, active + event.participant).copy(query = "")
            }

            is SearchEvent.RemoveParticipant -> state.update { state ->
                val active = state.activeParticipants(event.direction)
                state.withParticipants(event.direction, active.filterNot { it.id == event.participant.id })
            }
        }
    }
}

data class SearchState(
    val query: String = "",
    val overmailAccount: OvermailAccount? = null,

    /** What the label search found for [query]; the active ones among them stay, ticked. */
    val suggestedLabels: List<Label> = emptyList(),
    val activeLabels: List<Label> = emptyList(),

    /** The people the search for [query] found, most written with first. */
    val suggestedParticipants: List<Participant> = emptyList(),
    val activeSentBy: List<Participant> = emptyList(),
    val activeSentTo: List<Participant> = emptyList(),
) {
    val activeLabelIds: Set<Uuid> = activeLabels.map { it.id }.toSet()
    val activeSentByIds: Set<Uuid> = activeSentBy.map { it.id }.toSet()
    val activeSentToIds: Set<Uuid> = activeSentTo.map { it.id }.toSet()

    /** Whether the search narrows the listing at all: something typed or something picked. */
    val isActive: Boolean =
        query.isNotBlank() || activeLabels.isNotEmpty() || activeSentBy.isNotEmpty() || activeSentTo.isNotEmpty()

    fun activeParticipants(direction: ParticipantDirection) = when (direction) {
        ParticipantDirection.From -> activeSentBy
        ParticipantDirection.To -> activeSentTo
    }

    fun withParticipants(direction: ParticipantDirection, participants: List<Participant>) = when (direction) {
        ParticipantDirection.From -> copy(activeSentBy = participants)
        ParticipantDirection.To -> copy(activeSentTo = participants)
    }
}

/** Which side of a mail a person is searched on: who sent it, or who it went to. */
enum class ParticipantDirection { From, To }

sealed class SearchEvent {
    data class SetQuery(val query: String) : SearchEvent()
    /** Back to no search at all: nothing typed, nothing picked. */
    data object Reset : SearchEvent()
    data class ToggleLabel(val label: Label) : SearchEvent()
    data class RemoveLabel(val label: Label) : SearchEvent()
    data class ToggleParticipant(val participant: Participant, val direction: ParticipantDirection) : SearchEvent()
    data class RemoveParticipant(val participant: Participant, val direction: ParticipantDirection) : SearchEvent()
}
