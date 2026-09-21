@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package es.jvbabi.overmail.page.home.components.filter

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
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
import es.jvbabi.overmail.utils.animatePlacement
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
    onQueryChange: (String) -> Unit,
    onToggle: (Uuid) -> Unit,
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
    ) {
        LabelPickerContent(
            picked = picked,
            query = query,
            results = results,
            onQueryChange = onQueryChange,
            onToggle = onToggle,
            modifier = Modifier.fillMaxSize().imePadding(),
        )
    }
}

@Composable
private fun LabelPickerContent(
    picked: List<PickedLabel>,
    query: String,
    results: List<Labels>,
    onQueryChange: (String) -> Unit,
    onToggle: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    focusOnStart: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
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
        // happens rather than somewhere above it. The box grows and shrinks with the rows rather
        // than jumping, since the list below moves with it.
        FlowRow(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // Only the empty field says what it is for; once chips are in it, they do.
            // Fading and scaling only: a width that animates makes the row wrap anew on every
            // frame. What the change moves glides there instead, see animatePlacement.
            AnimatedVisibility(
                visible = picked.isEmpty(),
                enter = fadeIn() + scaleIn(initialScale = .6f),
                exit = fadeOut() + scaleOut(targetScale = .6f),
                modifier = Modifier.animatePlacement(),
            ) {
                Icon(
                    imageVector = PhIcons.Regular.MagnifyingGlass,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedChips(picked) { label, visibility ->
                AnimatedVisibility(
                    visibleState = visibility,
                    enter = fadeIn() + scaleIn(initialScale = .8f),
                    exit = fadeOut() + scaleOut(targetScale = .8f),
                    modifier = Modifier.animatePlacement(),
                ) {
                    PickedLabelChip(label = label, onRemove = { onToggle(label.id) })
                }
            }

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .widthIn(min = 96.dp)
                    .weight(1f)
                    .animatePlacement()
                    .padding(vertical = 8.dp)
                    .focusRequester(focusRequester),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) Text(
                            text = stringResource(Res.string.home_labels_search),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        field()
                    }
                },
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))

        // No creating from here: a filter picks among the labels there are, and one made on the
        // spot carries no mail to find.
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            // In the list rather than above it, so it takes the rows' place instead of pushing
            // them down while an answer is on its way.
            if (results.isEmpty()) item(key = "empty") {
                Text(
                    text = stringResource(Res.string.home_labels_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            items(results, key = { it.id.toString() }) { label ->
                LabelRow(
                    label = label,
                    picked = label.id in pickedIds,
                    onClick = { onToggle(label.id) },
                    // The server's answer following the cache reorders rows; they slide there.
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/**
 * [picked] with the ones just taken out kept around until they have animated away, in the order
 * they were picked. [content] gets each label with the state its visibility should follow.
 */
@Composable
private fun AnimatedChips(
    picked: List<PickedLabel>,
    content: @Composable (PickedLabel, MutableTransitionState<Boolean>) -> Unit,
) {
    // What is on screen at first is there already, not animated in: the sheet opening is motion
    // enough.
    val shown = remember { mutableStateListOf<PickedLabel>().apply { addAll(picked) } }
    val visibility = remember {
        mutableStateMapOf<Uuid, MutableTransitionState<Boolean>>().apply {
            picked.forEach { put(it.id, MutableTransitionState(true)) }
        }
    }

    LaunchedEffect(picked) {
        val ids = picked.map { it.id }.toSet()
        picked.forEach { label ->
            val index = shown.indexOfFirst { it.id == label.id }
            if (index >= 0) shown[index] = label else shown.add(label)
            visibility.getOrPut(label.id) { MutableTransitionState(false) }.targetState = true
        }
        shown.forEach { if (it.id !in ids) visibility[it.id]?.targetState = false }
    }

    shown.forEach { label ->
        val state = visibility[label.id] ?: return@forEach
        key(label.id) {
            content(label, state)
            if (state.isIdle && !state.currentState) LaunchedEffect(Unit) {
                shown.removeAll { it.id == label.id }
                visibility.remove(label.id)
            }
        }
    }
}

@Composable
private fun PickedLabelChip(label: PickedLabel, onRemove: () -> Unit) {
    val name = label.name ?: "…"
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(name) },
        leadingIcon = {
            Icon(
                imageVector = PhIcons.Regular.Tag,
                contentDescription = null,
                modifier = Modifier.size(InputChipDefaults.IconSize),
            )
        },
        // The fill is the whole edge, as on the web's tinted badge.
        border = null,
        colors = InputChipDefaults.inputChipColors(
            containerColor = label.color?.labelContainerColor()
                ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconColor = label.color?.labelContentColor()
                ?: MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        trailingIcon = {
            Icon(
                imageVector = PhIcons.Regular.X,
                contentDescription = stringResource(Res.string.home_labels_remove, name),
                modifier = Modifier.size(InputChipDefaults.IconSize),
            )
        },
    )
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
        Icon(
            imageVector = PhIcons.Regular.Tag,
            contentDescription = null,
            tint = label.color.labelContentColor(),
            modifier = Modifier.size(18.dp),
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
        // Always the room for it, so the counts stay in one column.
        Box(modifier = Modifier.size(18.dp)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = picked,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Icon(
                    imageVector = PhIcons.Regular.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
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
                onQueryChange = { query = it },
                onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
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
