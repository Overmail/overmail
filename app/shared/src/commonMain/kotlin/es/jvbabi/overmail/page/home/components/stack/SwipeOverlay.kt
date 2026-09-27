package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.bold.ArchiveBold
import com.phosphor.icons.bold.CheckBold
import com.phosphor.icons.bold.SkipForwardBold
import es.jvbabi.overmail.ui.theme.BaseColor
import es.jvbabi.overmail.ui.theme.baseColors
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_stack_overlay_archive
import overmail.app.shared.generated.resources.home_stack_overlay_let_go
import overmail.app.shared.generated.resources.home_stack_overlay_pull
import overmail.app.shared.generated.resources.home_stack_overlay_skip

/**
 * What a card pulled aside is about to do, over the whole card: fading in as it is pulled, the
 * action on the side it is pulled away from. [progress] is the way to the threshold, 1 there;
 * [isEnough] once letting go would do it.
 */
@Composable
internal fun SwipeOverlay(
    towards: StackSwipe,
    progress: Float,
    isEnough: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = towards.colors()
    val side = when (towards) {
        StackSwipe.Archive -> Alignment.End
        StackSwipe.Keep -> Alignment.Start
    }

    CompositionLocalProvider(LocalContentColor provides colors.onContainer) {
        Box(
            modifier = modifier
                .alpha(progress.coerceIn(0f, 1f))
                .fillMaxSize()
                .background(colors.container),
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxSize(),
                horizontalAlignment = side,
            ) {
                SwipeIndicator(towards = towards, progress = progress, isEnough = isEnough, colors = colors)
                Spacer(Modifier.height(24.dp))
                SwipeHint(towards = towards, isEnough = isEnough, side = side)
            }
        }
    }
}

/** The action's icon in a ring that fills up to the threshold, and a check once it is reached. */
@Composable
private fun SwipeIndicator(towards: StackSwipe, progress: Float, isEnough: Boolean, colors: BaseColor) {
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(),
            progress = { progress },
            color = LocalContentColor.current,
            trackColor = Color.Transparent,
        )
        AnimatedContent(
            targetState = isEnough,
            modifier = Modifier.size(48.dp),
            transitionSpec = { scaleIn() togetherWith scaleOut() },
        ) { isEnough ->
            if (isEnough) Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(colors.onContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = PhIcons.Bold.CheckBold,
                    contentDescription = null,
                    tint = colors.container,
                    modifier = Modifier.size(32.dp),
                )
            } else Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (towards) {
                        StackSwipe.Archive -> PhIcons.Bold.ArchiveBold
                        StackSwipe.Keep -> PhIcons.Bold.SkipForwardBold
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = LocalContentColor.current,
                )
            }
        }
    }
}

/** "Pull to archive", turning into "Let go to archive" once letting go would do it. */
@Composable
private fun SwipeHint(towards: StackSwipe, isEnough: Boolean, side: Alignment.Horizontal) {
    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.headlineSmall) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(.75f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, side),
        ) {
            AnimatedContent(targetState = isEnough) { isEnough ->
                Text(stringResource(if (isEnough) Res.string.home_stack_overlay_let_go else Res.string.home_stack_overlay_pull))
            }
            Text(
                stringResource(
                    when (towards) {
                        StackSwipe.Archive -> Res.string.home_stack_overlay_archive
                        StackSwipe.Keep -> Res.string.home_stack_overlay_skip
                    }
                )
            )
        }
    }
}

@Composable
private fun StackSwipe.colors(): BaseColor = when (this) {
    StackSwipe.Archive -> MaterialTheme.baseColors.emerald
    StackSwipe.Keep -> MaterialTheme.baseColors.blue
}
