package es.jvbabi.overmail.page.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.MagnifyingGlass
import com.phosphor.icons.regular.TreeStructure
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.jvbabi.overmail.page.home.components.filter.Chip
import es.jvbabi.overmail.page.home.components.filter.FilterStatesModal
import es.jvbabi.overmail.page.home.components.filter.archiveFilterStates
import es.jvbabi.overmail.page.home.components.filter.readFilterStates
import es.jvbabi.overmail.page.home.components.filter.LabelModal
import es.jvbabi.overmail.page.home.components.filter.PickedLabel
import es.jvbabi.overmail.page.home.components.filter.ViewController
import es.jvbabi.overmail.page.home.components.group.GroupModal
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_filter_archive_title
import overmail.app.shared.generated.resources.home_filter_read_title
import overmail.app.shared.generated.resources.home_grouping_title

@Composable
fun HomeScreen() {
    val viewSettingsViewModel = koinViewModel<ViewSettingsViewModel>()
    val viewSettingsState by viewSettingsViewModel.state.collectAsStateWithLifecycle()

    HomeContent(
        viewSettingsState = viewSettingsState,
        onViewSettingsEvent = viewSettingsViewModel::onEvent,
    )
}

@Composable
private fun HomeContent(
    viewSettingsState: ViewSettingsState,
    onViewSettingsEvent: (ViewSettingsEvent) -> Unit,
) {
    val viewState = viewSettingsState.viewState
    val pickedLabels = viewState.filter.hasLabels.orEmpty().map { id ->
        val label = viewSettingsState.knownLabels[id]
        PickedLabel(id = id, name = label?.name, color = label?.color)
    }

    var showGroupSettings by rememberSaveable { mutableStateOf(false) }
    var showLabelPicker by rememberSaveable { mutableStateOf(false) }
    var showReadStates by rememberSaveable { mutableStateOf(false) }
    var showArchiveStates by rememberSaveable { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp + innerPadding.calculateBottomPadding())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = "",
                    onValueChange = {},
                    leadingIcon = {
                        Icon(
                            imageVector = PhIcons.Regular.MagnifyingGlass,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    placeholder = { Text("Q2 Budget report") },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(percent = 50),
                )

                Row(
                    modifier = Modifier
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
                        onFilterChange = { onViewSettingsEvent(ViewSettingsEvent.SetFilter(it)) },
                        pickedLabels = pickedLabels,
                        readSelection = viewSettingsState.readSelection,
                        onReadSelectionChange = { onViewSettingsEvent(ViewSettingsEvent.SetReadSelection(it)) },
                        onReadMenuClick = { showReadStates = true },
                        onArchiveMenuClick = { showArchiveStates = true },
                        onLabelsClick = {
                            // Opening starts over: the query from last time says nothing about this one.
                            onViewSettingsEvent(ViewSettingsEvent.SetLabelQuery(""))
                            showLabelPicker = true
                        },
                    )
                }
            }
        }
    }

    GroupModal(
        visible = showGroupSettings,
        viewState = viewState,
        onGroupingSettingsChanged = { onViewSettingsEvent(ViewSettingsEvent.SetGroupingSettings(it)) },
        onDismiss = { showGroupSettings = false },
    )

    LabelModal(
        visible = showLabelPicker,
        picked = pickedLabels,
        query = viewSettingsState.labelQuery,
        results = viewSettingsState.labelResults,
        isFetching = viewSettingsState.isFetchingLabels,
        onQueryChange = { onViewSettingsEvent(ViewSettingsEvent.SetLabelQuery(it)) },
        onToggle = { onViewSettingsEvent(ViewSettingsEvent.ToggleLabel(it)) },
        onRemove = { onViewSettingsEvent(ViewSettingsEvent.RemoveLabel(it)) },
        onDismiss = { showLabelPicker = false },
    )

    FilterStatesModal(
        visible = showReadStates,
        title = stringResource(Res.string.home_filter_read_title),
        states = readFilterStates(),
        selected = viewSettingsState.readSelection,
        onSelectedChange = { onViewSettingsEvent(ViewSettingsEvent.SetReadSelection(it)) },
        onDismiss = { showReadStates = false },
    )

    FilterStatesModal(
        visible = showArchiveStates,
        title = stringResource(Res.string.home_filter_archive_title),
        states = archiveFilterStates(),
        selected = viewState.filter.archivedState.orEmpty(),
        onSelectedChange = {
            // Nothing ticked is the null that restricts nothing, see ViewFilter.
            onViewSettingsEvent(ViewSettingsEvent.SetFilter(viewState.filter.copy(archivedState = it.ifEmpty { null })))
        },
        onDismiss = { showArchiveStates = false },
    )
}

@Composable
@Preview
private fun HomeContentPreview() {
    HomeContent(viewSettingsState = ViewSettingsState(), onViewSettingsEvent = {})
}
