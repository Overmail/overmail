package es.jvbabi.overmail.utils

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurDefaults
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/** The way a progressive effect fades out: full strength where it starts, none where it ends. */
enum class ProgressiveDirection {
    TopToBottom,
    BottomToTop,
}

/**
 * Blurs whatever [hazeState] captured behind this element, fading from [startRadius] to no blur
 * at all along [direction]. Pair it with [dev.chrisbanes.haze.hazeSource] and the same
 * [hazeState] on the content underneath.
 *
 * @param backgroundColor the surface the captured content is drawn on. A source without a
 * background of its own is blurred onto transparency, and the sharp content underneath would show
 * through the blur.
 * @param easing how the blur fades out, from the edge it starts at (0) to where it ends (1). One
 * that ends flat, like the default, lets the blur run out without a visible edge.
 */
fun Modifier.progressiveBackgroundBlur(
    hazeState: HazeState,
    direction: ProgressiveDirection,
    backgroundColor: Color,
    startRadius: Dp = HazeBlurDefaults.blurRadius,
    easing: Easing = EaseInOut,
): Modifier = hazeBlur(
    input = HazeInput.Backdrop(hazeState),
    style = HazeBlurStyle {
        backgroundColor(backgroundColor)
        blurRadius(startRadius)
        progressive(
            when (direction) {
                ProgressiveDirection.TopToBottom -> HazeProgressive.verticalGradient(
                    easing = easing,
                    startIntensity = 1f,
                    endIntensity = 0f,
                )

                // Haze always runs top to bottom, so the curve is mirrored to start at the bottom.
                ProgressiveDirection.BottomToTop -> HazeProgressive.verticalGradient(
                    easing = MirroredEasing(easing),
                    startIntensity = 0f,
                    endIntensity = 1f,
                )
            },
        )
    },
)

/**
 * Fills this element with [color], fading to transparent along [direction]. Placed after
 * [progressiveBackgroundBlur] it tints the blur, so whatever sits on top stays readable.
 *
 * @param easing how the color fades out, like the one of [progressiveBackgroundBlur].
 */
fun Modifier.progressiveBackground(
    color: Color,
    direction: ProgressiveDirection,
    easing: Easing = EaseInOut,
): Modifier {
    // Brush gradients are linear between stops, so the curve is sampled into enough of them.
    val fromEdge = List(GRADIENT_STOPS) { i ->
        color.copy(alpha = color.alpha * (1f - easing.transform(i / (GRADIENT_STOPS - 1f))))
    }
    return background(
        Brush.verticalGradient(
            when (direction) {
                ProgressiveDirection.TopToBottom -> fromEdge
                ProgressiveDirection.BottomToTop -> fromEdge.asReversed()
            },
        ),
    )
}

private const val GRADIENT_STOPS = 20

/**
 * [easing] seen from the other end. A data class so that Haze, which compares its styles, does
 * not rebuild the effect on every recomposition.
 */
private data class MirroredEasing(val easing: Easing) : Easing {
    override fun transform(fraction: Float): Float = 1f - easing.transform(1f - fraction)
}
