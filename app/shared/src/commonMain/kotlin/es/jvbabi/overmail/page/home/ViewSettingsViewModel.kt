@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.overmail.page.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.Correspondent
import es.jvbabi.overmail.domain.model.Label
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.model.ViewFilter
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.LabelsRepository
import es.jvbabi.overmail.domain.repository.ParticipantsRepository
import es.jvbabi.overmail.page.home.components.filter.ReadState
import es.jvbabi.overmail.page.home.components.filter.readStateOf
import es.jvbabi.overmail.page.home.components.group.GroupingSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * The view the listing shows -- filter, groupings, sorting -- and what its settings need loaded to
 * be edited, such as the labels a filter can be put on.
 */
class ViewSettingsViewModel(
    accountRepository: AccountRepository,
    private val labelsRepository: LabelsRepository,
    private val participantsRepository: ParticipantsRepository,
) : ViewModel() {
    val state: StateFlow<ViewSettingsState>
        field = MutableStateFlow(ViewSettingsState())

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
                state.map { it.viewState.filter.hasLabels.orEmpty() }.distinctUntilChanged(),
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
                state.map { current ->
                    (current.viewState.filter.sentBy.orEmpty() + current.viewState.filter.sentTo.orEmpty())
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

    fun onEvent(event: ViewSettingsEvent) {
        when (event) {
            is ViewSettingsEvent.SetFilter -> state.update { it.copy(viewState = it.viewState.copy(filter = event.filter)) }
            is ViewSettingsEvent.SetGroupingSettings -> state.update {
                it.copy(viewState = it.viewState.copy(groupings = event.settings.groupings, sorting = event.settings.sorting))
            }
            is ViewSettingsEvent.SetReadSelection -> state.update {
                it.copy(
                    readSelection = event.selection,
                    viewState = it.viewState.copy(filter = it.viewState.filter.copy(readState = readStateOf(event.selection))),
                )
            }
            is ViewSettingsEvent.SetLabelQuery -> state.update { it.copy(labelQuery = event.query) }
            is ViewSettingsEvent.SetParticipantQuery -> state.update { it.copy(participantQuery = event.query) }
            is ViewSettingsEvent.ToggleCorrespondent -> state.update { current ->
                val list = current.viewState.filter.correspondents(event.target)
                val toggled = if (event.correspondent in list) list - event.correspondent else list + event.correspondent
                current.copy(
                    // Nobody picked is no filter, not one that lets nothing through.
                    viewState = current.viewState.withCorrespondents(event.target, toggled.ifEmpty { null }),
                    participantQuery = "",
                )
            }
            is ViewSettingsEvent.RemoveCorrespondent -> state.update { current ->
                val list = current.viewState.filter.correspondents(event.target) - event.correspondent
                current.copy(viewState = current.viewState.withCorrespondents(event.target, list.ifEmpty { null }))
            }
            is ViewSettingsEvent.RemoveLabel -> state.update { current ->
                val filter = current.viewState.filter
                val hasLabels = filter.hasLabels.orEmpty().minus(event.id).ifEmpty { null }
                current.copy(viewState = current.viewState.copy(filter = filter.copy(hasLabels = hasLabels)))
            }
            is ViewSettingsEvent.ToggleLabel -> state.update { current ->
                val filter = current.viewState.filter
                val ids = filter.hasLabels.orEmpty()
                val toggled = if (event.id in ids) ids - event.id else ids + event.id
                // Nothing picked is no label filter, not one that lets nothing through.
                val hasLabels = toggled.ifEmpty { null }
                current.copy(
                    viewState = current.viewState.copy(filter = filter.copy(hasLabels = hasLabels)),
                    // What was typed has done its job once its label is picked, as in the web app.
                    labelQuery = "",
                )
            }
        }
    }
}

/** Which side of a mail a correspondent filter is about. */
enum class CorrespondentTarget { From, To }

private fun ViewFilter.correspondents(target: CorrespondentTarget): List<Correspondent> = when (target) {
    CorrespondentTarget.From -> sentBy
    CorrespondentTarget.To -> sentTo
}.orEmpty()

private fun ViewState.withCorrespondents(target: CorrespondentTarget, correspondents: List<Correspondent>?): ViewState =
    copy(
        filter = when (target) {
            CorrespondentTarget.From -> filter.copy(sentBy = correspondents)
            CorrespondentTarget.To -> filter.copy(sentTo = correspondents)
        }
    )

data class ViewSettingsState(
    val viewState: ViewState = ViewState.Mailbox,
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
)

sealed class ViewSettingsEvent {
    data class SetFilter(val filter: ViewFilter) : ViewSettingsEvent()
    data class SetGroupingSettings(val settings: GroupingSettings) : ViewSettingsEvent()
    data class SetReadSelection(val selection: List<ReadState>) : ViewSettingsEvent()
    data class SetLabelQuery(val query: String) : ViewSettingsEvent()
    /** Takes the label off the filter; unlike [ToggleLabel], what is typed stays. */
    data class RemoveLabel(val id: Uuid) : ViewSettingsEvent()
    /** Puts the label on the filter, or takes it off when it already is. */
    data class ToggleLabel(val id: Uuid) : ViewSettingsEvent()
    data class SetParticipantQuery(val query: String) : ViewSettingsEvent()
    /** Puts the correspondent on the [target] filter, or takes them off; empties the query. */
    data class ToggleCorrespondent(val target: CorrespondentTarget, val correspondent: Correspondent) : ViewSettingsEvent()
    /** Takes the correspondent off the [target] filter; what is typed stays. */
    data class RemoveCorrespondent(val target: CorrespondentTarget, val correspondent: Correspondent) : ViewSettingsEvent()
}
