package es.jvbabi.overmail.page.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.MagnifyingGlass
import es.jvbabi.overmail.ui.theme.AppTheme

private val SEARCH_FIELD_HEIGHT = 40.dp

/**
 * A single filled line, lower than a Material text field: it sits in the header of the
 * list, where every dp it takes is one less of the mails.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(SEARCH_FIELD_HEIGHT)
            .clip(RoundedCornerShape(percent = 50))
            // What a filled Material text field is filled with.
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = PhIcons.Regular.MagnifyingGlass,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(
                        text = placeholder,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    innerTextField()
                }
            }
        },
    )
}

@Composable
@Preview
private fun SearchFieldPreview() {
    AppTheme(dynamicColor = false) {
        Surface {
            SearchField(
                value = "",
                onValueChange = {},
                placeholder = "Q2 Budget report",
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
            )
        }
    }
}
