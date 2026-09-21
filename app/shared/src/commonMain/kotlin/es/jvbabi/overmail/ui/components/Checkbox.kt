package es.jvbabi.overmail.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Check
import com.phosphor.icons.regular.Minus
import es.jvbabi.overmail.ui.theme.AppTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement

/**
 * The web app's shadcn checkbox (`ui/checkbox`): a small square with softened corners, filled
 * with the input grey when off and with the primary color, a tick on it, when on. No border. The
 * fill fades over and the tick springs in, where the web one snaps.
 *
 * [size] scales the corners and the tick with it; the web's is 16px next to 14px text, the
 * default here sits next to body text.
 *
 * Without [onCheckedChange] it only shows the state, for a row that takes the click as a whole.
 */
@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    /** Neither on nor off, shown as a dash; wins over [checked] only while that is false. */
    indeterminate: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 20.dp,
) {
    val colors = MaterialTheme.colorScheme
    val filled = checked || indeterminate
    // The web's `bg-input/90`: a neutral grey, which in the dark is a faint white.
    val inputColor = colors.onSurface.copy(alpha = if (colors.surface.luminance() < 0.5f) 0.15f else 0.1f)

    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(size * 5 / 16))
            .background(animateColorAsState(if (filled) colors.primary else inputColor).value)
            .then(
                if (onCheckedChange == null) Modifier
                else Modifier.toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = filled,
            enter = fadeIn() + scaleIn(
                initialScale = 0.4f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            ),
            exit = fadeOut() + scaleOut(targetScale = 0.4f),
        ) {
            Icon(
                imageVector = if (checked || !indeterminate) PhIcons.Regular.Check else PhIcons.Regular.Minus,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(size * 14 / 16),
            )
        }
    }
}

@Composable
@Preview
private fun CheckboxPreview() {
    AppTheme(dynamicColor = false) {
        Surface {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = false, onCheckedChange = {})
                Checkbox(checked = true, onCheckedChange = {})
                Checkbox(checked = false, onCheckedChange = {}, indeterminate = true)
                Checkbox(checked = true, onCheckedChange = {}, enabled = false)
            }
        }
    }
}
