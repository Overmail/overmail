@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.overmail.page.home.components.filter

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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

/** Low enough that the filters take one slim line under the search. */
internal val CHIP_HEIGHT = 30.dp

/** The icons in a chip, its own and those put in as [Chip]'s leading. */
internal val CHIP_ICON_SIZE = 16.dp

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
        val defaultRoundedCornerRadius = CHIP_HEIGHT / 2
        val defaultPressedCornerRadius = 4.dp
        val defaultInnerCornerRadius = 0.dp
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.height(CHIP_HEIGHT),
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
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                leading.invoke()
                // A filter's chip says what it is set to, so its text changes under a tap; it
                // fades over and the chip's width follows rather than jumping.
                AnimatedContent(
                    targetState = text,
                    transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) },
                ) { current ->
                    Text(current, style = MaterialTheme.typography.labelMedium)
                }
                if (arrowDown && !segmented) Icon(
                    imageVector = PhIcons.Regular.CaretDown,
                    contentDescription = null,
                    modifier = Modifier.size(CHIP_ICON_SIZE)
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
            modifier = Modifier.size(CHIP_HEIGHT),
        ) {
            if (arrowDown) Icon(
                imageVector = PhIcons.Regular.CaretDown,
                contentDescription = null,
                modifier = Modifier.size(CHIP_ICON_SIZE)
            )
        }
    }
}