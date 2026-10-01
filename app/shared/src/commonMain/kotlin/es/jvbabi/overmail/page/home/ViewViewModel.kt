@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package es.jvbabi.overmail.page.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.Correspondent
import es.jvbabi.overmail.domain.model.ViewFilter
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.domain.repository.ParticipantsRepository
import es.jvbabi.overmail.domain.repository.ViewResult
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
        field = MutableStateFlow(ViewState.MailboxWithArchive)

    /** What the search has picked, laid over [viewState] for the listing, see [withSearch]. */
    private val searchFilter = MutableStateFlow(SearchFilter())

    /**
     * What is typed into the search, apart from the rest: it changes with every key, and each
     * change opens the server's stream anew, so it waits for a pause. Emptying it does not.
     */
    private val searchQuery = MutableStateFlow("")

    val content: StateFlow<ViewContentState>
        field = MutableStateFlow(ViewContentState())

    // There is no account switcher yet, so the listing is the first account's.
    private val account = accountRepository.getAccounts()
        .map { it.firstOrNull() }
        .distinctUntilChanged()

    private val emailBodies = EmailBodies(viewModelScope, emailsRepository::getPicture, emailsRepository::getBody)

    /** What the mails of the listing say, as far as a preview has asked for it, see [ViewEvent.LoadBody]. */
    val bodies: StateFlow<Map<Uuid, StackCardBody>> = emailBodies.bodies

    init {
        viewModelScope.launch {
            combine(
                account,
                viewState,
                searchFilter,
                searchQuery.debounce { if (it.isBlank()) 0 else SEARCH_QUERY_DEBOUNCE_MILLIS },
            ) { account, view, search, query -> account to view.withSearch(search.copy(query = query)) }
                .distinctUntilChanged()
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
            is ViewEvent.SetSearchFilter -> {
                searchFilter.value = event.filter.copy(query = "")
                searchQuery.value = event.filter.query
            }
            is ViewEvent.LoadBody -> viewModelScope.launch {
                val account = account.first() ?: return@launch
                // One mail is previewed at a time, and it is wanted now: ahead of whatever the pile
                // is having pictured.
                emailBodies.keepPictures(setOf(event.emailId))
                emailBodies.load(event.emailId, account, order = { PREVIEW_RENDER_ORDER })
            }
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

/** Where a previewed mail goes in line for its picture: before any card of the pile. */
private const val PREVIEW_RENDER_ORDER = -1

/** How long the listing waits for the next key before it searches for what is typed. */
private const val SEARCH_QUERY_DEBOUNCE_MILLIS = 250L

/**
 * What the search is on. Each kind narrows the listing on its own, any of its entries will do --
 * the view filter's own and-between, or-within, see [ViewFilter]. The [query] is one more kind:
 * every word of it has to turn up in a mail.
 */
data class SearchFilter(
    val labels: List<Uuid> = emptyList(),
    val sentBy: List<Uuid> = emptyList(),
    val sentTo: List<Uuid> = emptyList(),
    val query: String = "",
)

/**
 * The view with the search laid over it: what the search has picked of a kind stands in for the
 * view's own filter of that kind, a kind it has nothing of leaves the view's as it is.
 */
private fun ViewState.withSearch(search: SearchFilter): ViewState = copy(
    filter = filter.copy(
        hasLabels = search.labels.ifEmpty { null } ?: filter.hasLabels,
        sentBy = search.sentBy.ifEmpty { null }?.map(Correspondent::Contact) ?: filter.sentBy,
        sentTo = search.sentTo.ifEmpty { null }?.map(Correspondent::Contact) ?: filter.sentTo,
        query = search.query.trim().ifEmpty { null } ?: filter.query,
    ),
)

sealed class ViewEvent {
    data class SetViewState(val viewState: ViewState) : ViewEvent()

    /** The search picked or dropped a person or label. */
    data class SetSearchFilter(val filter: SearchFilter) : ViewEvent()

    /** A mail of the listing is previewed and needs what it says. */
    data class LoadBody(val emailId: Uuid) : ViewEvent()
}
