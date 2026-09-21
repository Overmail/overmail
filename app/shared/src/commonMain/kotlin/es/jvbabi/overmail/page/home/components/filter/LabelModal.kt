@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package es.jvbabi.overmail.page.home.components.filter

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Check
import com.phosphor.icons.regular.MagnifyingGlass
import com.phosphor.icons.regular.Tag
import com.phosphor.icons.regular.X
import es.jvbabi.overmail.domain.model.Labels
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.labelContainerColor
import es.jvbabi.overmail.utils.labelContentColor
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.*
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
    results: List<Labels>,
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
private fun LabelPickerContent(
    picked: List<PickedLabel>,
    query: String,
    results: List<Labels>,
    isFetching: Boolean,
    onQueryChange: (String) -> Unit,
    onToggle: (Uuid) -> Unit,
    onRemove: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    focusOnStart: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current

    // The field holds an invisible character ahead of the query. A soft keyboard sends nothing
    // for Backspace in an empty field, but it does delete that character, and that is how the
    // field tells a Backspace in front of the text from one inside it.
    var fieldValue by remember { mutableStateOf(sentinelValue(query, TextRange(query.length))) }
    // The query can change from outside too -- emptied after a label was picked.
    if (fieldValue.text.drop(1) != query) fieldValue = sentinelValue(query, TextRange(query.length))
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
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
                .animateContentSize()
                .padding(vertical = 16.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // Only the empty field says what it is for; once labels are in it, they do.
            if (picked.isEmpty()) Icon(
                imageVector = PhIcons.Regular.MagnifyingGlass,
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(18.dp),
            )

            picked.forEach { label ->
                key(label.id) {
                    LabelBadge(label = label, onRemove = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                        onRemove(label.id)
                    })
                }
            }

            // At least as wide as its placeholder, so the placeholder never wraps: where it does
            // not fit beside the badges any more, the field goes on a line of its own instead.
            val placeholder = stringResource(Res.string.home_labels_search)
            val placeholderStyle = MaterialTheme.typography.bodyLarge
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val minFieldWidth = remember(placeholder, placeholderStyle, density) {
                // A little over, for the caret behind it.
                with(density) { textMeasurer.measure(placeholder, placeholderStyle).size.width.toDp() } + 4.dp
            }

            BasicTextField(
                value = fieldValue,
                onValueChange = { changed ->
                    if (!changed.text.startsWith(BACKSPACE_SENTINEL)) {
                        // Backspace at the very start: it takes the label before the caret, the
                        // way every field that holds chips does. What was typed stays.
                        picked.lastOrNull()?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                            onRemove(it.id)
                        }
                        val text = changed.text.removePrefix(BACKSPACE_SENTINEL)
                        fieldValue = sentinelValue(text, TextRange(0))
                        if (text != query) onQueryChange(text)
                        return@BasicTextField
                    }
                    // The caret never goes before the sentinel, or typing would land in front of it.
                    fieldValue = changed.copy(
                        selection = TextRange(
                            changed.selection.start.coerceAtLeast(1),
                            changed.selection.end.coerceAtLeast(1),
                        ),
                    )
                    val text = changed.text.drop(1)
                    if (text != query) onQueryChange(text)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .widthIn(min = minFieldWidth)
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .focusRequester(focusRequester),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) Text(
                            text = placeholder,
                            style = placeholderStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                        field()
                    }
                },
            )
        }

        // The divider swells into the progress while the server is asked, and back once it
        // answered.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            androidx.compose.animation.AnimatedVisibility(
                visible = isFetching,
                enter = expandVertically(expandFrom = Alignment.CenterVertically) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.CenterVertically) + fadeOut(),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

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
                LabelRow(
                    label = label,
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

/** Zero width, so the field looks empty with it; see where [LabelPickerContent] uses it. */
private const val BACKSPACE_SENTINEL = "\u200B"

/** [query] behind the sentinel, with [selection] counted in the query. */
private fun sentinelValue(query: String, selection: TextRange) = TextFieldValue(
    text = BACKSPACE_SENTINEL + query,
    selection = TextRange(selection.start + 1, selection.end + 1),
)

/**
 * A picked label in the field, the web app's removable tinted `Badge`: a small square-cornered
 * tag in the label's hue, the name in the normal text color and an X to take it out.
 */
@Composable
private fun LabelBadge(label: PickedLabel, onRemove: () -> Unit) {
    val name = label.name ?: "…"
    Row(
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(label.color?.labelContainerColor() ?: MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Icon(
            imageVector = PhIcons.Regular.X,
            contentDescription = stringResource(Res.string.home_labels_remove, name),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable(onClick = onRemove)
                .size(14.dp),
        )
    }
}

/** One row of the list: menu-sized rather than a full list item, so a screen holds many. */
@Composable
private fun LabelRow(
    label: Labels,
    picked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The tick takes the tag's place rather than a column of its own, flipping over to it and
        // back. Past the halfway point the other face shows, turned round so it does not read
        // mirrored.
        val rotation by animateFloatAsState(targetValue = if (picked) 180f else 0f)
        Icon(
            imageVector = if (rotation > 90f) PhIcons.Regular.Check else PhIcons.Regular.Tag,
            contentDescription = null,
            tint = label.color.labelContentColor(),
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    rotationY = if (rotation > 90f) rotation - 180f else rotation
                    cameraDistance = 12f * density
                },
        )
        Text(
            text = label.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = pluralStringResource(
                Res.plurals.home_labels_email_count,
                label.emailCount.toInt(),
                label.emailCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
@Preview
private fun LabelPickerContentPreview() {
    val labels = listOf(
        PreviewLabel("Uni", Color(0xFFD6E4F5), 128),
        PreviewLabel("Rechnungen", Color(0xFFF5DDD6), 42),
        PreviewLabel("HPI", Color(0xFFDDF5D6), 1),
    ).mapIndexed { index, (name, color, count) ->
        Labels(
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

private data class PreviewLabel(val name: String, val color: Color, val count: Long)

private val PREVIEW_ACCOUNT = OvermailAccount(
    id = Uuid.fromLongs(0, 0),
    username = "preview",
    firstName = "Preview",
    lastName = "User",
    email = "preview@example.com",
    homeserver = "https://example.com",
    token = "",
)
