@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.overmail.page.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.domain.repository.ParticipantsRepository
import es.jvbabi.overmail.domain.repository.ViewResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * The view the listing shows -- filter, groupings, sorting -- and the mails in it. What goes into
 * editing the view is [ViewSettingsViewModel]'s, which hands the result back through
 * [ViewEvent.SetViewState].
 */
class ViewViewModel(
    accountRepository: AccountRepository,
    private val emailsRepository: EmailsRepository,
    private val participantsRepository: ParticipantsRepository,
) : ViewModel() {
    val viewState: StateFlow<ViewState>
        field = MutableStateFlow(ViewState.Mailbox)

    val content: StateFlow<ViewContentState>
        field = MutableStateFlow(ViewContentState())

    // There is no account switcher yet, so the listing is the first account's.
    private val account = accountRepository.getAccounts()
        .map { it.firstOrNull() }
        .distinctUntilChanged()

    init {
        viewModelScope.launch {
            combine(account, viewState, ::Pair)
                .flatMapLatest { (account, view) ->
                    if (account == null) flowOf(ViewContentState(isLoading = false))
                    // A view that changed shows what it had until the new one is read, marked
                    // as loading rather than emptied.
                    else emailsRepository.getView(view, instantLocalEmission = true, user = account)
                        .flatMapLatest { results ->
                            val senderIds = results.senderIds()
                            if (senderIds.isEmpty()) flowOf(ViewContentState(results = results, isLoading = false))
                            else participantsRepository.getByIds(senderIds.toList(), account).map { senders ->
                                ViewContentState(results = results, senders = senders.associateBy { it.id }, isLoading = false)
                            }
                        }
                        .onStart { content.update { it.copy(isLoading = true) } }
                }
                .collect { content.value = it }
        }
    }

    fun onEvent(event: ViewEvent) {
        when (event) {
            is ViewEvent.SetViewState -> viewState.value = event.viewState
        }
    }
}

/**
 * What the current view holds, as the local database has it; [EmailsRepository.getView] keeps
 * that in step with the server.
 */
data class ViewContentState(
    /** The groups of the view, or its mails when it is not grouped. */
    val results: List<ViewResult> = emptyList(),
    /** The correspondents the sender groups stand for, as far as this device knows them. */
    val senders: Map<Uuid, Participant> = emptyMap(),
    /** Until the first answer for the current view is in. */
    val isLoading: Boolean = true,
)

/** Every sender a group in here stands for, however deep it is nested. */
private fun List<ViewResult>.senderIds(): Set<Uuid> = buildSet {
    fun visit(results: List<ViewResult>) {
        results.forEach { result ->
            if (result !is ViewResult.Group) return@forEach
            if (result is ViewResult.Group.Sender) add(result.participantId)
            visit(result.items)
        }
    }
    visit(this@senderIds)
}

sealed class ViewEvent {
    data class SetViewState(val viewState: ViewState) : ViewEvent()
}
