package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.bold.ArchiveBold
import com.phosphor.icons.bold.ArrowBendUpLeftBold
import com.phosphor.icons.bold.SkipForwardBold
import es.jvbabi.overmail.page.home.HandledEmail
import es.jvbabi.overmail.page.home.PREVIEW_ITEMS
import es.jvbabi.overmail.ui.theme.AppTheme
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_stack_no_subject
import overmail.app.shared.generated.resources.home_stack_toolbar_archived
import overmail.app.shared.generated.resources.home_stack_toolbar_progress
import overmail.app.shared.generated.resources.home_stack_toolbar_skipped
import overmail.app.shared.generated.resources.home_stack_toolbar_undo

/**
 * Under the pile once a card has been swiped: what happened to the last one, with a button that
 * takes it back, see [onUndo], and how far through the pile the reader is -- [done] of [total],
 * which also fills the bar from the left.
 */
@Composable
fun StackToolbar(
    last: HandledEmail,
    done: Int,
    total: Int,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(if (total == 0) 0f else done.toFloat() / total)
    val filled = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    Surface(
        modifier = modifier
            .fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .drawBehind { drawRect(filled, size = size.copy(width = size.width * progress)) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Changes with every swipe: the new one comes up from below, the old one goes up.
            AnimatedContent(
                targetState = last,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                },
            ) { last ->
                LastSwipe(last)
            }

            Text(
                text = stringResource(Res.string.home_stack_toolbar_progress, done, total),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            FilledTonalButton(
                onClick = onUndo,
                contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
            ) {
                Icon(
                    imageVector = PhIcons.Bold.ArrowBendUpLeftBold,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.home_stack_toolbar_undo))
            }
        }
    }
}

/** The swipe's own icon in its colors, what it did, and to which mail. */
@Composable
private fun LastSwipe(last: HandledEmail) {
    val colors = last.swipe.colors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(colors.container, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (last.swipe) {
                    StackSwipe.Archive -> PhIcons.Bold.ArchiveBold
                    StackSwipe.Keep -> PhIcons.Bold.SkipForwardBold
                },
                contentDescription = null,
                tint = colors.onContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier.padding(start = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(
                    when (last.swipe) {
                        StackSwipe.Archive -> Res.string.home_stack_toolbar_archived
                        StackSwipe.Keep -> Res.string.home_stack_toolbar_skipped
                    }
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = last.email.subject ?: stringResource(Res.string.home_stack_no_subject),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview
@Composable
private fun StackToolbarPreview() {
    AppTheme(dynamicColor = false) {
        StackToolbar(
            last = HandledEmail(PREVIEW_ITEMS.first().email, StackSwipe.Archive),
            done = 3,
            total = 5,
            onUndo = {},
        )
    }
}
