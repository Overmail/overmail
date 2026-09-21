@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.overmail.page.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.Labels
import es.jvbabi.overmail.domain.model.ViewFilter
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.LabelsRepository
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

    fun onEvent(event: ViewSettingsEvent) {
        when (event) {
            is ViewSettingsEvent.SetFilter -> state.update { it.copy(viewState = it.viewState.copy(filter = event.filter)) }
            is ViewSettingsEvent.SetGroupingSettings -> state.update {
                it.copy(viewState = it.viewState.copy(groupings = event.settings.groupings, sorting = event.settings.sorting))
            }
            is ViewSettingsEvent.SetLabelQuery -> state.update { it.copy(labelQuery = event.query) }
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

data class ViewSettingsState(
    val viewState: ViewState = ViewState.Mailbox,
    /** What is typed into the label picker. */
    val labelQuery: String = "",
    /** The labels [labelQuery] turns up, most used first. */
    val labelResults: List<Labels> = emptyList(),
    /** Whether the server's answer to [labelQuery] is still on its way; the results are the cache's. */
    val isFetchingLabels: Boolean = false,
    /**
     * The labels of the filter's `hasLabels`, as far as they are cached. An id missing here is
     * still in the filter, it only cannot be named yet.
     */
    val knownLabels: Map<Uuid, Labels> = emptyMap(),
)

sealed class ViewSettingsEvent {
    data class SetFilter(val filter: ViewFilter) : ViewSettingsEvent()
    data class SetGroupingSettings(val settings: GroupingSettings) : ViewSettingsEvent()
    data class SetLabelQuery(val query: String) : ViewSettingsEvent()
    /** Takes the label off the filter; unlike [ToggleLabel], what is typed stays. */
    data class RemoveLabel(val id: Uuid) : ViewSettingsEvent()
    /** Puts the label on the filter, or takes it off when it already is. */
    data class ToggleLabel(val id: Uuid) : ViewSettingsEvent()
}
