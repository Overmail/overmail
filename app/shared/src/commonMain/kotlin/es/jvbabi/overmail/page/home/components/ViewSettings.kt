package es.jvbabi.overmail.page.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.TreeStructure
import es.jvbabi.overmail.domain.model.Correspondent
import es.jvbabi.overmail.domain.model.ViewFilter
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.page.home.ViewSettingsEvent
import es.jvbabi.overmail.page.home.ViewSettingsViewModel
import es.jvbabi.overmail.page.home.components.filter.*
import es.jvbabi.overmail.page.home.components.group.GroupModal
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import overmail.app.shared.generated.resources.*
import kotlin.uuid.Uuid

/**
 * The chips that set up [viewState] -- grouping and filters -- and the modals they open. Edits
 * leave through [onViewStateChange]; what the modals need loaded is [ViewSettingsViewModel]'s,
 * which this holds itself.
 */
@Composable
fun ViewSettings(
    viewState: ViewState,
    onViewStateChange: (ViewState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<ViewSettingsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // What is loaded follows the view: the names of its labels and contacts.
    LaunchedEffect(viewState) { viewModel.onEvent(ViewSettingsEvent.SetView(viewState)) }

    val onFilterChange = { filter: ViewFilter -> onViewStateChange(viewState.copy(filter = filter)) }
    val onReadSelectionChange = { selection: List<ReadState> ->
        viewModel.onEvent(ViewSettingsEvent.SetReadSelection(selection))
        onFilterChange(viewState.filter.copy(readState = readStateOf(selection)))
    }

    val selfName = stringResource(Res.string.home_participants_self)
    val namesOf = { correspondents: List<Correspondent>? ->
        correspondents.orEmpty().map { correspondent ->
            when (correspondent) {
                Correspondent.Self -> selfName
                is Correspondent.Contact -> state.knownParticipants[correspondent.id]?.displayName ?: "…"
            }
        }
    }
    val pickedLabels = viewState.filter.hasLabels.orEmpty().map { id ->
        val label = state.knownLabels[id]
        PickedLabel(id = id, name = label?.name, color = label?.color)
    }

    var showGroupSettings by rememberSaveable { mutableStateOf(false) }
    var showLabelPicker by rememberSaveable { mutableStateOf(false) }
    var showReadStates by rememberSaveable { mutableStateOf(false) }
    var showArchiveStates by rememberSaveable { mutableStateOf(false) }
    var showFromPicker by rememberSaveable { mutableStateOf(false) }
    var showToPicker by rememberSaveable { mutableStateOf(false) }
    var showAccounts by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(
            text = stringResource(Res.string.home_grouping_title),
            leading = {
                Icon(
                    imageVector = PhIcons.Regular.TreeStructure,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = { showGroupSettings = true }
        )

        Spacer(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .height(32.dp)
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        ViewController(
            viewState = viewState,
            onFilterChange = onFilterChange,
            pickedLabels = pickedLabels,
            readSelection = state.readSelection,
            onReadSelectionChange = onReadSelectionChange,
            onReadMenuClick = { showReadStates = true },
            onArchiveMenuClick = { showArchiveStates = true },
            fromNames = namesOf(viewState.filter.sentBy),
            toNames = namesOf(viewState.filter.sentTo),
            onFromClick = {
                // Opening starts over, as with the labels.
                viewModel.onEvent(ViewSettingsEvent.SetParticipantQuery(""))
                showFromPicker = true
            },
            accountNames = viewState.filter.imapAccountIds.orEmpty().map { id ->
                state.imapAccounts.firstOrNull { it.id == id }?.username ?: "…"
            },
            onAccountsClick = {
                // Read afresh as it opens: a mailbox connected elsewhere should be in it.
                viewModel.onEvent(ViewSettingsEvent.RefreshImapAccounts)
                showAccounts = true
            },
            onToClick = {
                viewModel.onEvent(ViewSettingsEvent.SetParticipantQuery(""))
                showToPicker = true
            },
            onLabelsClick = {
                // Opening starts over: the query from last time says nothing about this one.
                viewModel.onEvent(ViewSettingsEvent.SetLabelQuery(""))
                showLabelPicker = true
            },
        )
    }

    GroupModal(
        visible = showGroupSettings,
        viewState = viewState,
        onGroupingSettingsChanged = {
            onViewStateChange(viewState.copy(groupings = it.groupings, sorting = it.sorting))
        },
        onDismiss = { showGroupSettings = false },
    )

    LabelModal(
        visible = showLabelPicker,
        picked = pickedLabels,
        query = state.labelQuery,
        results = state.labelResults,
        isFetching = state.isFetchingLabels,
        onQueryChange = { viewModel.onEvent(ViewSettingsEvent.SetLabelQuery(it)) },
        onToggle = {
            onFilterChange(viewState.filter.toggleLabel(it))
            // What was typed has done its job once its label is picked, as in the web app.
            viewModel.onEvent(ViewSettingsEvent.SetLabelQuery(""))
        },
        // Unlike picking, taking a label off leaves what is typed.
        onRemove = { onFilterChange(viewState.filter.removeLabel(it)) },
        onDismiss = { showLabelPicker = false },
    )

    FilterStatesModal(
        visible = showReadStates,
        title = stringResource(Res.string.home_filter_read_title),
        states = readFilterStates(),
        selected = state.readSelection,
        onSelectedChange = onReadSelectionChange,
        onDismiss = { showReadStates = false },
    )

    FilterStatesModal(
        visible = showArchiveStates,
        title = stringResource(Res.string.home_filter_archive_title),
        states = archiveFilterStates(),
        selected = viewState.filter.archivedState.orEmpty(),
        onSelectedChange = {
            // Nothing ticked is the null that restricts nothing, see ViewFilter.
            onFilterChange(viewState.filter.copy(archivedState = it.ifEmpty { null }))
        },
        onDismiss = { showArchiveStates = false },
    )

    ImapAccountsModal(
        visible = showAccounts,
        accounts = state.imapAccounts,
        selected = viewState.filter.imapAccountIds.orEmpty(),
        isFetching = state.isFetchingImapAccounts,
        failed = state.imapAccountsFailed,
        onToggle = { onFilterChange(viewState.filter.toggleImapAccount(it)) },
        onDismiss = { showAccounts = false },
    )

    CorrespondentTarget.entries.forEach { target ->
        ParticipantModal(
            visible = if (target == CorrespondentTarget.From) showFromPicker else showToPicker,
            title = stringResource(if (target == CorrespondentTarget.From) Res.string.home_filter_from else Res.string.home_filter_to),
            picked = viewState.filter.correspondents(target),
            known = state.knownParticipants,
            query = state.participantQuery,
            results = state.participantResults,
            isFetching = state.isFetchingParticipants,
            onQueryChange = { viewModel.onEvent(ViewSettingsEvent.SetParticipantQuery(it)) },
            onToggle = {
                onFilterChange(viewState.filter.toggleCorrespondent(target, it))
                viewModel.onEvent(ViewSettingsEvent.SetParticipantQuery(""))
            },
            // What is typed stays, as with the labels.
            onRemove = { onFilterChange(viewState.filter.removeCorrespondent(target, it)) },
            onDismiss = { if (target == CorrespondentTarget.From) showFromPicker = false else showToPicker = false },
        )
    }
}

/** Which side of a mail a correspondent filter is about. */
private enum class CorrespondentTarget { From, To }

// Nothing picked is no filter, not one that lets nothing through: every edit below that can empty
// a list leaves null instead.

private fun <T> List<T>.toggle(item: T): List<T> = if (item in this) this - item else this + item

private fun ViewFilter.toggleLabel(id: Uuid) = copy(hasLabels = hasLabels.orEmpty().toggle(id).ifEmpty { null })

private fun ViewFilter.removeLabel(id: Uuid) = copy(hasLabels = hasLabels.orEmpty().minus(id).ifEmpty { null })

private fun ViewFilter.toggleImapAccount(id: Uuid) =
    copy(imapAccountIds = imapAccountIds.orEmpty().toggle(id).ifEmpty { null })

private fun ViewFilter.correspondents(target: CorrespondentTarget): List<Correspondent> = when (target) {
    CorrespondentTarget.From -> sentBy
    CorrespondentTarget.To -> sentTo
}.orEmpty()

private fun ViewFilter.withCorrespondents(target: CorrespondentTarget, correspondents: List<Correspondent>): ViewFilter {
    val value = correspondents.ifEmpty { null }
    return when (target) {
        CorrespondentTarget.From -> copy(sentBy = value)
        CorrespondentTarget.To -> copy(sentTo = value)
    }
}

private fun ViewFilter.toggleCorrespondent(target: CorrespondentTarget, correspondent: Correspondent) =
    withCorrespondents(target, correspondents(target).toggle(correspondent))

private fun ViewFilter.removeCorrespondent(target: CorrespondentTarget, correspondent: Correspondent) =
    withCorrespondents(target, correspondents(target) - correspondent)
