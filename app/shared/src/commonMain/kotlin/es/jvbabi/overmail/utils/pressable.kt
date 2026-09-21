package es.jvbabi.overmail.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * A click without a ripple: the element shrinks a little while pressed and answers with a light
 * haptic tick. For options in a card, where a ripple would flood the row.
 *
 * @param haptic what the click feels like, asked at the moment of the click so a toggle can tell
 *   on from off; null for none.
 */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    haptic: () -> HapticFeedbackType? = { HapticFeedbackType.ContextClick },
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f)
    val haptics = LocalHapticFeedback.current

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = interactionSource, indication = null, enabled = enabled) {
            haptic()?.let(haptics::performHapticFeedback)
            onClick()
        }
}
