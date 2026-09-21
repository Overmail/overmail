package es.jvbabi.overmail.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * The app's full-width action button: label on the left, an optional icon on the right, and a
 * spinner in place of both while [ButtonState.Loading].
 *
 * A click is only passed on while [ButtonState.Enabled] (unless [onlyEventOnActive] is off), so a
 * loading button cannot be sent twice.
 */
@Composable
fun Button(
    modifier: Modifier = Modifier,
    text: String,
    icon: DrawableResource? = null,
    state: ButtonState = ButtonState.Enabled,
    size: ButtonSize = ButtonSize.Big,
    type: ButtonType = ButtonType.Primary,
    onlyEventOnActive: Boolean = true,
    center: Boolean = false,
    onClick: () -> Unit,
) {
    val clickEvent = { if (!onlyEventOnActive || state == ButtonState.Enabled) onClick() }
    val enabled = state != ButtonState.Disabled
    val shape = RoundedCornerShape(8.dp)
    val buttonModifier = modifier.then(
        when (size) {
            ButtonSize.Big -> Modifier.defaultMinSize(minHeight = 56.dp).fillMaxWidth()
            ButtonSize.Normal -> Modifier.defaultMinSize(minHeight = 48.dp).fillMaxWidth()
            ButtonSize.Small -> Modifier.defaultMinSize(minHeight = 48.dp).animateContentSize(tween())
        }
    )
    val content: @Composable () -> Unit = {
        // Keyed on what is shown, not on the state: enabled and disabled show the same label, and
        // only the button's colours differ between them -- switching those must not re-run this.
        AnimatedContent(targetState = state == ButtonState.Loading) { isLoading ->
            Box(
                modifier = if (size == ButtonSize.Small) Modifier else Modifier.fillMaxWidth(),
                contentAlignment = if (center) Alignment.Center else Alignment.CenterEnd,
            ) {
                when (isLoading) {
                    true -> CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )

                    false -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Keeps a centred label centred against the icon on the other side.
                        if (icon != null && center) Spacer(Modifier.width(24.dp))
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = if (center) TextAlign.Center else TextAlign.Start,
                            modifier = Modifier.weight(1f),
                        )
                        if (icon != null) Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).padding(2.dp),
                        )
                    }
                }
            }
        }
    }

    when (type) {
        ButtonType.Outlined -> OutlinedButton(
            onClick = clickEvent,
            enabled = enabled,
            shape = shape,
            modifier = buttonModifier,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            content()
        }

        ButtonType.Primary, ButtonType.Secondary -> androidx.compose.material3.Button(
            onClick = clickEvent,
            enabled = enabled,
            shape = shape,
            modifier = buttonModifier,
            colors = when (type) {
                ButtonType.Secondary -> ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceDim,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
                else -> ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
        ) {
            content()
        }
    }
}

enum class ButtonSize {
    Big, Normal, Small
}

enum class ButtonType {
    Primary, Secondary, Outlined
}

enum class ButtonState {
    Enabled, Disabled, Loading
}
