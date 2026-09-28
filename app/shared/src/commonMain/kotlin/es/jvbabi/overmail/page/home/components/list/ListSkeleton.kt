package es.jvbabi.overmail.page.home.components.list

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the list shows while it loads, top to bottom: more than a screen holds. */
val LIST_SKELETON: List<SkeletonRow> = buildList {
    listOf(3, 4, 2, 5).forEach { rows ->
        add(SkeletonRow.Header)
        repeat(rows) { add(SkeletonRow.Mail) }
    }
}

enum class SkeletonRow { Header, Mail }

/** Items composed this long after [ListReveal.reveal] are part of it; later ones were scrolled to. */
private val REVEAL_WINDOW = 300.milliseconds

/** Between two items coming in, top one first. */
private const val REVEAL_STAGGER = 40L

/** Past this many the stagger stops growing, so nothing waits long for its turn. */
private const val REVEAL_STAGGER_MAX_ITEMS = 10

/**
 * When the list replaced its skeleton with the mails, so those that came in with it animate in
 * and those scrolled to later are simply there. Not state: nothing redraws because of it, it is
 * only asked while an item is first composed.
 */
class ListReveal {
    private var revealedAt: TimeMark? = null
    private var showedSkeleton = false

    /** Told every composition whether the skeleton is up; its going away is the reveal. */
    fun update(showsSkeleton: Boolean) {
        if (showedSkeleton && !showsSkeleton) revealedAt = TimeSource.Monotonic.markNow()
        showedSkeleton = showsSkeleton
    }

    internal fun isRevealing(): Boolean = revealedAt?.let { it.elapsedNow() < REVEAL_WINDOW } == true
}

/** The [index]th item of the list, coming up into place and fading in if it is part of a reveal. */
@Composable
fun Modifier.revealIn(reveal: ListReveal, index: Int): Modifier {
    val progress = remember { Animatable(if (reveal.isRevealing()) 0f else 1f) }
    LaunchedEffect(progress) {
        if (progress.value == 1f) return@LaunchedEffect
        delay(index.coerceAtMost(REVEAL_STAGGER_MAX_ITEMS) * REVEAL_STAGGER)
        progress.animateTo(1f, tween(durationMillis = 350, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
    }
}

/** One pulse for every placeholder of the list, so they breathe together. */
@Composable
fun rememberSkeletonPulse(): State<Float> = rememberInfiniteTransition().animateFloat(
    initialValue = 0.4f,
    targetValue = 0.9f,
    animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
)

/** A [row] of the skeleton, shaped like a [ViewGroupComponent] header or a [ViewItem]. */
@Composable
fun ListSkeletonRow(
    row: SkeletonRow,
    pulse: State<Float>,
    modifier: Modifier = Modifier,
) {
    val pulsing = modifier.graphicsLayer { alpha = pulse.value }
    when (row) {
        SkeletonRow.Header -> Column(pulsing.padding(start = 66.dp, top = 8.dp, bottom = 6.dp)) {
            Bone(width = 96.dp, height = 14.dp)
        }

        SkeletonRow.Mail -> Row(
            modifier = pulsing
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Bone(width = 36.dp, height = 36.dp, corner = 8.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Bone(width = 120.dp, height = 12.dp)
                    Bone(width = 36.dp, height = 10.dp)
                }
                Bone(fraction = 0.7f, height = 14.dp)
                Bone(fraction = 0.9f, height = 10.dp)
            }
        }
    }
}

@Composable
private fun Bone(
    height: Dp,
    width: Dp? = null,
    fraction: Float = 1f,
    corner: Dp = height / 2,
) {
    Box(
        Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(fraction))
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
@Preview
private fun ListSkeletonPreview() {
    AppTheme(dynamicColor = false) {
        Surface {
            val pulse = rememberSkeletonPulse()
            Column {
                LIST_SKELETON.forEach { ListSkeletonRow(row = it, pulse = pulse) }
            }
        }
    }
}
