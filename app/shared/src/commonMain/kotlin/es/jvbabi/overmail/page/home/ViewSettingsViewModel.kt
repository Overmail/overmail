@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.overmail.page.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.Correspondent
import es.jvbabi.overmail.domain.model.ImapAccount
import es.jvbabi.overmail.domain.model.Label
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.ImapAccountsRepository
import es.jvbabi.overmail.domain.repository.LabelsRepository
import es.jvbabi.overmail.domain.repository.ParticipantsRepository
import es.jvbabi.overmail.page.home.components.filter.ReadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * What the settings of a view need loaded to be edited, such as the labels a filter can be put on.
 * The view is not this one's: it is told the current one through [ViewSettingsEvent.SetView].
 */
class ViewSettingsViewModel(
    accountRepository: AccountRepository,
    private val labelsRepository: LabelsRepository,
    private val participantsRepository: ParticipantsRepository,
    private val imapAccountsRepository: ImapAccountsRepository,
) : ViewModel() {
    val state: StateFlow<ViewSettingsState>
        field = MutableStateFlow(ViewSettingsState())

    /** The view the settings are shown for; the labels and contacts it names are loaded. */
    private val view = MutableStateFlow(ViewState.Mailbox)

    /** Bumped to read the mailboxes afresh; they are read at start and whenever their card opens. */
    private val imapAccountsRefresh = MutableStateFlow(0)

    // There is no account switcher yet, so the settings are the first account's.
    private val account = accountRepository.getAccounts()
        .map { it.firstOrNull() }
        .distinctUntilChanged()

    init {
        viewModelScope.launch {
            combine(
                account,
                // A request per keystroke, as in the web app: the cache answers at once, and the
                // request for the previous query is cancelled by the next one.
                state.map { it.labelQuery.trim() }.distinctUntilChanged(),
                ::Pair,
            )
                .flatMapLatest { (account, query) ->
                    if (account == null) flowOf(CacheableResource(emptyList(), CacheableResource.Source.Fallback))
                    // The picker is typed into; a result that waits up to five seconds for the
                    // server is worse than one that is corrected a moment later.
                    else labelsRepository.search(query = query, instantLocalEmission = true, overmailAccount = account)
                }
                .collect { labels ->
                    state.update { it.copy(labelResults = labels.data, isFetchingLabels = labels.isFetching) }
                }
        }

        viewModelScope.launch {
            combine(
                account,
                view.map { it.filter.hasLabels.orEmpty() }.distinctUntilChanged(),
                ::Pair,
            )
                .flatMapLatest { (account, ids) ->
                    if (account == null || ids.isEmpty()) flowOf(emptyList())
                    else labelsRepository.getByIds(ids, account)
                }
                .collect { labels -> state.update { it.copy(knownLabels = labels.associateBy { label -> label.id }) } }
        }
    }

    init {
        // One search for both the from and the to picker: only one of them is ever open.
        viewModelScope.launch {
            combine(account, state.map { it.participantQuery.trim() }.distinctUntilChanged(), ::Pair)
                .flatMapLatest { (account, query) ->
                    if (account == null) flowOf(CacheableResource(emptyList(), CacheableResource.Source.Fallback))
                    else participantsRepository.search(query = query, instantLocalEmission = true, overmailAccount = account)
                }
                .collect { participants ->
                    state.update { it.copy(participantResults = participants.data, isFetchingParticipants = participants.isFetching) }
                }
        }

        viewModelScope.launch {
            combine(
                account,
                view.map { current ->
                    (current.filter.sentBy.orEmpty() + current.filter.sentTo.orEmpty())
                        .filterIsInstance<Correspondent.Contact>()
                        .map { it.id }
                        .distinct()
                }.distinctUntilChanged(),
                ::Pair,
            )
                .flatMapLatest { (account, ids) ->
                    if (account == null || ids.isEmpty()) flowOf(emptyList())
                    else participantsRepository.getByIds(ids, account)
                }
                .collect { participants ->
                    state.update { it.copy(knownParticipants = participants.associateBy { participant -> participant.id }) }
                }
        }
    }

    init {
        viewModelScope.launch {
            combine(account, imapAccountsRefresh, ::Pair)
                .flatMapLatest { (account, _) ->
                    if (account == null) flowOf(CacheableResource(emptyList(), CacheableResource.Source.Fallback))
                    // Fresh rather than fast: the list is small, and it is what the chip names.
                    // Until the answer or the timeout, what was read before stays in the state.
                    else imapAccountsRepository.getAll(instantLocalEmission = false, overmailAccount = account)
                }
                .collect { accounts ->
                    state.update {
                        it.copy(
                            imapAccounts = accounts.data,
                            isFetchingImapAccounts = accounts.isFetching,
                            imapAccountsFailed = accounts.source == CacheableResource.Source.Fallback,
                        )
                    }
                }
        }
    }

    fun onEvent(event: ViewSettingsEvent) {
        when (event) {
            is ViewSettingsEvent.SetView -> view.value = event.view
            is ViewSettingsEvent.SetReadSelection -> state.update { it.copy(readSelection = event.selection) }
            is ViewSettingsEvent.SetLabelQuery -> state.update { it.copy(labelQuery = event.query) }
            is ViewSettingsEvent.SetParticipantQuery -> state.update { it.copy(participantQuery = event.query) }
            ViewSettingsEvent.RefreshImapAccounts -> {
                state.update { it.copy(isFetchingImapAccounts = true) }
                imapAccountsRefresh.update { it + 1 }
            }
        }
    }
}

data class ViewSettingsState(
    /**
     * What the read filter has ticked; the filter's `readState` follows it, but cannot tell both
     * from neither.
     */
    val readSelection: List<ReadState> = emptyList(),
    /** What is typed into the label picker. */
    val labelQuery: String = "",
    /** The labels [labelQuery] turns up, most used first. */
    val labelResults: List<Label> = emptyList(),
    /** Whether the server's answer to [labelQuery] is still on its way; the results are the cache's. */
    val isFetchingLabels: Boolean = false,
    /**
     * The labels of the filter's `hasLabels`, as far as they are cached. An id missing here is
     * still in the filter, it only cannot be named yet.
     */
    val knownLabels: Map<Uuid, Label> = emptyMap(),
    /** What is typed into the from or to picker, whichever is open. */
    val participantQuery: String = "",
    val participantResults: List<Participant> = emptyList(),
    val isFetchingParticipants: Boolean = false,
    /** The contacts of the filter's `sentBy` and `sentTo`, as far as they are cached. */
    val knownParticipants: Map<Uuid, Participant> = emptyMap(),
    /** Every mailbox of the account, as fresh as the last read. */
    val imapAccounts: List<ImapAccount> = emptyList(),
    val isFetchingImapAccounts: Boolean = true,
    /** The last read could not reach the server; [imapAccounts] is what the cache had. */
    val imapAccountsFailed: Boolean = false,
)

sealed class ViewSettingsEvent {
    data class SetView(val view: ViewState) : ViewSettingsEvent()
    /** Only what the read chip shows ticked; the filter it makes is the caller's to set. */
    data class SetReadSelection(val selection: List<ReadState>) : ViewSettingsEvent()
    data class SetLabelQuery(val query: String) : ViewSettingsEvent()
    data class SetParticipantQuery(val query: String) : ViewSettingsEvent()
    /** Reads the mailboxes afresh, as the accounts card opens. */
    data object RefreshImapAccounts : ViewSettingsEvent()
}
