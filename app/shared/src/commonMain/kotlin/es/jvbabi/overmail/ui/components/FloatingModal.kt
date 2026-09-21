@file:OptIn(ExperimentalMaterial3Api::class)

package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A bottom sheet as a card that floats above the bottom edge: rounded on every corner, clear of
 * the screen's sides, only as tall as what it holds, and set off from the screen by a hairline and
 * a soft shadow rather than by a heavy veil. Rows inside round with
 * [FloatingModalDefaults.ItemCornerRadius].
 *
 * The sheet itself stays edge to edge and is only made invisible -- dragging, the scrim and back
 * all remain the sheet's. A margin on the sheet would leave it resting on that margin once hidden.
 */
@Composable
fun FloatingModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(visible) {
        if (visible) state.show() else state.hide()
    }

    // Composed only while it is meant to be seen or still sliding out, see GroupModal.
    if (!visible && !state.isVisible) return

    val colors = MaterialTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(0.dp),
        containerColor = Color.Transparent,
        // A lighter veil than a full sheet's: the card is small and the screen stays readable.
        scrimColor = Color.Black.copy(alpha = if (isDark) 0.5f else 0.24f),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(FloatingModalDefaults.CornerRadius),
            // Lifted off what is behind it: near white in the light, a step up in the dark, where
            // a shadow has nothing to fall on.
            color = if (isDark) colors.surfaceContainerHigh else colors.surfaceContainerLowest,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = if (isDark) 0.4f else 0.6f)),
            shadowElevation = if (isDark) 0.dp else 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(
                    start = FloatingModalDefaults.Padding,
                    end = FloatingModalDefaults.Padding,
                    bottom = FloatingModalDefaults.Padding,
                ),
            ) {
                // Says the card can be pulled down, without asking for attention.
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(colors.onSurfaceVariant.copy(alpha = 0.25f)),
                )
                content()
            }
        }
    }
}

/**
 * The card's proportions, for what goes into it: a row inside rounds with the card's radius less
 * the padding around it, so the two curves run parallel -- the web's menus do the same.
 */
object FloatingModalDefaults {
    val CornerRadius = 32.dp
    val Padding = 8.dp
    val ItemCornerRadius = CornerRadius - Padding
}
