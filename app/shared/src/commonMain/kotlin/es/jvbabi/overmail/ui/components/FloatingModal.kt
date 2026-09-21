@file:OptIn(ExperimentalMaterial3Api::class)

package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
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
 * the screen's sides, and only as tall as what it holds.
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(0.dp),
        containerColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = BottomSheetDefaults.ContainerColor,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp), content = content)
        }
    }
}
