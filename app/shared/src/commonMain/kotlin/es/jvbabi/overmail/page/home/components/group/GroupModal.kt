@file:OptIn(ExperimentalMaterial3Api::class)

package es.jvbabi.overmail.page.home.components.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.ViewState
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_grouping_title

@Composable
fun GroupModal(
    visible: Boolean,
    viewState: ViewState,
    onGroupingSettingsChanged: (GroupingSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    // While a category is dragged, the sheet holds still: the same finger would otherwise pull it
    // down along with the row.
    var draggingCategory by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) state.show() else state.hide()
    }

    // Composed only while it is meant to be seen or still sliding out: a ModalBottomSheet opens
    // itself when it enters the composition and puts its window over the screen, whatever the
    // state says. Once hide() has run its course, isVisible drops and the sheet leaves.
    if (!visible && !state.isVisible) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = Modifier.fillMaxSize(),
        dragHandle = null,
        sheetGesturesEnabled = !draggingCategory,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(Res.string.home_grouping_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            // The content scrolls itself; it is a lazy list, and one inside a scrolling column
            // would have no height to lay out in.
            GroupingSettingsContent(
                viewState = viewState,
                onGroupingSettingsChanged = onGroupingSettingsChanged,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                onDraggingChanged = { draggingCategory = it },
            )
        }
    }
}
