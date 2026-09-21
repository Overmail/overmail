package es.jvbabi.overmail.page.home.components.filter.label_search

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.MagnifyingGlass
import com.phosphor.icons.regular.X
import es.jvbabi.overmail.utils.labelContainerColor
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_labels_remove
import kotlin.uuid.Uuid

data class PickedItem(
    val id: Uuid,
    val name: String,
    val color: Color,
    /** In front of the name, a person's face say; nothing for a label. */
    val leading: (@Composable () -> Unit)? = null,
)

@Composable
fun LabelTextField(
    picked: List<PickedItem>,
    placeholder: String,
    focusRequester: FocusRequester,
    query: String,
    onRemove: (Uuid) -> Unit,
    onQueryChange: (String) -> Unit,
) {
    // The field holds an invisible character ahead of the query. A soft keyboard sends nothing
    // for Backspace in an empty field, but it does delete that character, and that is how the
    // field tells a Backspace in front of the text from one inside it.
    var fieldValue by remember { mutableStateOf(sentinelValue(query, TextRange(query.length))) }
    // The query can change from outside too -- emptied after a label was picked.
    if (fieldValue.text.drop(1) != query) fieldValue = sentinelValue(query, TextRange(query.length))
    val haptics = LocalHapticFeedback.current
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

        picked.forEach { item ->
            key(item.id) {
                ItemBadge(item = item, onRemove = {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                    onRemove(item.id)
                })
            }
        }

        // At least as wide as its placeholder, so the placeholder never wraps: where it does
        // not fit beside the badges any more, the field goes on a line of its own instead.
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
}

/**
 * A picked label in the field, the web app's removable tinted `Badge`: a small square-cornered
 * tag in the label's hue, the name in the normal text color and an X to take it out.
 */
@Composable
private fun ItemBadge(
    item: PickedItem,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(item.color.labelContainerColor())
            .padding(start = if (item.leading == null) 6.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item.leading?.invoke()
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Icon(
            imageVector = PhIcons.Regular.X,
            contentDescription = stringResource(Res.string.home_labels_remove, item.name),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable(onClick = onRemove)
                .size(14.dp),
        )
    }
}