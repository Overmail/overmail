package es.jvbabi.overmail.page.agent.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowUp
import es.jvbabi.overmail.ui.components.orb.OrbState
import es.jvbabi.overmail.ui.components.orb.ThinkingOrb
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.agent_input_placeholder
import overmail.app.shared.generated.resources.agent_send

private val SEND_SIZE = 40.dp

/**
 * Where a question is written: a pill floating over the conversation, the send button inside it.
 * While an answer is being written the button holds the orb instead of the arrow -- the prompt
 * waits, and says why without words.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    isAnswering: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val canSend = !isAnswering && value.isNotBlank()
    val colors = MaterialTheme.colorScheme
    val buttonColor by animateColorAsState(
        targetValue = if (canSend) colors.onSurface else colors.surfaceContainerLow,
        animationSpec = tween(150),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(28.dp), ambientColor = colors.scrim.copy(alpha = 0.2f), spotColor = colors.scrim.copy(alpha = 0.2f))
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainerHigh)
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                // Centred on the button while it is one line, growing upwards from there.
                .padding(vertical = 9.dp),
        ) {
            if (value.isEmpty()) Text(
                text = stringResource(Res.string.agent_input_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .size(SEND_SIZE)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(
                    enabled = canSend,
                    role = Role.Button,
                    onClickLabel = stringResource(Res.string.agent_send),
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSend()
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isAnswering,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.6f)) togetherWith
                        (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.6f))
                },
            ) { answering ->
                if (answering) ThinkingOrb(OrbState.Working)
                else Icon(
                    imageVector = PhIcons.Regular.ArrowUp,
                    contentDescription = stringResource(Res.string.agent_send),
                    tint = if (canSend) colors.surface else colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
