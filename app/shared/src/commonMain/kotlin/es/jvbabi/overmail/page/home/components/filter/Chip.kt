@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.overmail.page.home.components.filter

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.CaretDown

@Composable
fun Chip(
    modifier: Modifier = Modifier,
    text: String,
    leading: @Composable () -> Unit,
    arrowDown: Boolean = false,
    segmented: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit = {},
    onSegmentedClick: () -> Unit = {},
) {
    val baseColor = ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val activeColor = ButtonDefaults.buttonColors()
    val buttonColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = animateColorAsState(if (active) activeColor.containerColor else baseColor.containerColor).value,
        contentColor = animateColorAsState(if (active) activeColor.contentColor else baseColor.contentColor).value,
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val defaultRoundedCornerRadius = 18.dp
        val defaultPressedCornerRadius = 4.dp
        val defaultInnerCornerRadius = 0.dp
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.height(36.dp),
            colors = buttonColors,
            shapes = ButtonDefaults.shapes(
                shape = RoundedCornerShape(
                    topStart = defaultRoundedCornerRadius,
                    bottomStart = defaultRoundedCornerRadius,
                    topEnd = if (segmented) defaultInnerCornerRadius else defaultRoundedCornerRadius,
                    bottomEnd = if (segmented) defaultInnerCornerRadius else defaultRoundedCornerRadius
                ),
                pressedShape = RoundedCornerShape(defaultPressedCornerRadius),
            ),
            contentPadding = ButtonDefaults.contentPaddingFor(36.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                leading.invoke()
                Text(text)
                if (arrowDown && !segmented) Icon(
                    imageVector = PhIcons.Regular.CaretDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (segmented) IconButton(
            onClick = onSegmentedClick,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = buttonColors.containerColor,
                contentColor = buttonColors.contentColor,
            ),
            shapes = IconButtonDefaults.shapes(
                shape = RoundedCornerShape(
                    topStart = defaultInnerCornerRadius,
                    bottomStart = defaultInnerCornerRadius,
                    topEnd = defaultRoundedCornerRadius,
                    bottomEnd = defaultRoundedCornerRadius
                ),
                pressedShape = RoundedCornerShape(defaultPressedCornerRadius),
            ),
            modifier = Modifier.size(36.dp),
        ) {
            if (arrowDown) Icon(
                imageVector = PhIcons.Regular.CaretDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}