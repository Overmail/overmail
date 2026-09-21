@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package es.jvbabi.overmail.page.home.components.filter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.Label
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.page.home.components.filter.label_search.Item
import es.jvbabi.overmail.page.home.components.filter.label_search.LabelTextField
import es.jvbabi.overmail.page.home.components.filter.label_search.PickedItem
import es.jvbabi.overmail.page.home.components.filter.label_search.SearchDivider
import es.jvbabi.overmail.ui.theme.AppTheme
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_filter_labels
import overmail.app.shared.generated.resources.home_labels_empty
import overmail.app.shared.generated.resources.home_labels_search
import kotlin.uuid.Uuid

/** A label the filter is on, as far as it can be named; see [ViewController]. */
data class PickedLabel(
    val id: Uuid,
    /** Null while the label is not in the cache yet. */
    val name: String?,
    val color: Color?,
)

/**
 * Picks the labels a filter is on, the web app's `LabelFilter`: the picked ones sit in the search
 * field as removable chips, and the list below toggles rather than adds -- a label that is on has
 * to be pickable to turn it off again.
 *
 * Nothing about views: it is handed the picked labels and the search, and says what was toggled.
 */
@Composable
fun LabelModal(
    visible: Boolean,
    picked: List<PickedLabel>,
    query: String,
    results: List<Label>,
    /** Whether the server's answer is still on its way; [results] are the cache's until then. */
    isFetching: Boolean,
    onQueryChange: (String) -> Unit,
    /** A row of the list was picked. */
    onToggle: (Uuid) -> Unit,
    /** A badge was taken out of the field, by its X or Backspace. */
    onRemove: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(visible) {
        if (visible) state.show() else state.hide()
    }

    // See GroupModal: composed only while it is meant to be seen or still sliding out.
    if (!visible && !state.isVisible) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = Modifier.fillMaxSize(),
        dragHandle = null,
        // The bottom inset is the list's own, so it scrolls behind the navigation bar.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) },
    ) {
        LabelPickerContent(
            picked = picked,
            query = query,
            results = results,
            isFetching = isFetching,
            onQueryChange = onQueryChange,
            onToggle = onToggle,
            onRemove = onRemove,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun LabelPickerContent(
    picked: List<PickedLabel>,
    query: String,
    results: List<Label>,
    isFetching: Boolean,
    onQueryChange: (String) -> Unit,
    onToggle: (Uuid) -> Unit,
    onRemove: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    focusOnStart: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current

    // The list is driven by the field, so opening lands in it.
    if (focusOnStart) LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val pickedIds = remember(picked) { picked.map { it.id }.toSet() }

    Column(modifier = modifier) {
        Text(
            text = stringResource(Res.string.home_filter_labels),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )

        // The picked labels and the field share one box, so the selection is where the typing
        // happens rather than somewhere above it. Only its height animates, since the list below
        // moves with it; the badges themselves come and go at once, as in the web app.
        LabelTextField(
            focusRequester = focusRequester,
            picked = picked.map {
                PickedItem(
                    id = it.id,
                    name = it.name ?: "…",
                    color = it.color ?: MaterialTheme.colorScheme.secondary
                )
            },
            placeholder = stringResource(Res.string.home_labels_search),
            query = query,
            onRemove = onRemove,
            onQueryChange = onQueryChange,
        )

        SearchDivider(
            isFetching = isFetching,
        )

        // No creating from here: a filter picks among the labels there are, and one made on the
        // spot carries no mail to find.
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = 8.dp + WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding(),
            ),
        ) {
            // In the list rather than above it, so it takes the rows' place instead of pushing
            // them down while an answer is on its way.
            // Not while the server may still find something the cache does not have.
            if (results.isEmpty() && !isFetching) item(key = "empty") {
                Text(
                    text = stringResource(Res.string.home_labels_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            items(results, key = { it.id.toString() }) { label ->
                Item(
                    name = label.name,
                    color = label.color,
                    subtitle = null,
                    emailCount = label.emailCount,
                    picked = label.id in pickedIds,
                    onClick = {
                        haptics.performHapticFeedback(
                            if (label.id in pickedIds) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
                        )
                        onToggle(label.id)
                    },
                )
            }
        }
    }
}

private data class PreviewLabel(val name: String, val color: Color, val count: Long)

@Composable
@Preview
private fun LabelPickerContentPreview() {
    val labels = listOf(
        PreviewLabel("Uni", Color(0xFFD6E4F5), 128),
        PreviewLabel("Rechnungen", Color(0xFFF5DDD6), 42),
        PreviewLabel("HPI", Color(0xFFDDF5D6), 1),
    ).mapIndexed { index, (name, color, count) ->
        Label(
            id = Uuid.fromLongs(0, index.toLong()),
            name = name,
            color = color,
            emailCount = count,
            overmailAccount = PREVIEW_ACCOUNT,
        )
    }
    var picked by remember { mutableStateOf(setOf(labels[0].id)) }
    var query by remember { mutableStateOf("") }

    AppTheme(dynamicColor = false) {
        Surface {
            LabelPickerContent(
                picked = labels.filter { it.id in picked }.map { PickedLabel(it.id, it.name, it.color) },
                query = query,
                results = labels,
                isFetching = true,
                onQueryChange = { query = it },
                onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
                onRemove = { id -> picked = picked - id },
                modifier = Modifier.height(400.dp),
                focusOnStart = false,
            )
        }
    }
}

private val PREVIEW_ACCOUNT = OvermailAccount(
    id = Uuid.fromLongs(0, 0),
    username = "preview",
    firstName = "Preview",
    lastName = "User",
    email = "preview@example.com",
    homeserver = "https://example.com",
    token = "",
)
