package es.jvbabi.overmail.page.home.components.filter.label_search

import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun SearchDivider(
    isFetching: Boolean,
) {
    // The divider swells into the progress while the server is asked, and back once it
    // answered.
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        androidx.compose.animation.AnimatedVisibility(
            visible = isFetching,
            enter = expandVertically(expandFrom = Alignment.CenterVertically) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.CenterVertically) + fadeOut(),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}