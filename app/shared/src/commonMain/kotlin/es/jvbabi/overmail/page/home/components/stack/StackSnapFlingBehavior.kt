package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.abs

/**
 * Fling for the listing that lies over the pile. Its first [range] px of scrolling are the pile
 * giving way to the listing, and nothing in between is a place to rest: a fling that would stop in
 * there goes on to the pile in full or to the listing in full, whichever it is headed for. Past
 * [range] it is the usual decay -- until a fling back up runs into the stretch, which then snaps
 * the same way.
 */
@Composable
fun rememberStackSnapFlingBehavior(listState: LazyListState, range: Float): FlingBehavior {
    val decay = rememberSplineBasedDecay<Float>()
    return remember(listState, range, decay) { StackSnapFlingBehavior(listState, range, decay) }
}

private class StackSnapFlingBehavior(
    private val listState: LazyListState,
    private val range: Float,
    private val decay: DecayAnimationSpec<Float>,
) : FlingBehavior {
    /** How far the listing has pushed the pile away, or null once it is past [range]. */
    private fun positionInRange(): Float? {
        if (listState.firstVisibleItemIndex != 0) return null
        return listState.firstVisibleItemScrollOffset.toFloat().takeIf { it < range }
    }

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (range <= 0f) return decayFling(initialVelocity, stopInRange = false)
        positionInRange()?.let { return snap(it, initialVelocity) }

        val velocity = decayFling(initialVelocity, stopInRange = true)
        return positionInRange()?.let { snap(it, velocity) } ?: velocity
    }

    /** What the default fling does, and the velocity left over when it stopped early. */
    private suspend fun ScrollScope.decayFling(initialVelocity: Float, stopInRange: Boolean): Float {
        if (abs(initialVelocity) <= 1f) return initialVelocity
        var velocity = initialVelocity
        var last = 0f
        AnimationState(initialValue = 0f, initialVelocity = initialVelocity).animateDecay(decay) {
            val delta = value - last
            val consumed = scrollBy(delta)
            last = value
            velocity = this.velocity
            if (abs(delta - consumed) > 0.5f || (stopInRange && positionInRange() != null)) cancelAnimation()
        }
        return velocity
    }

    private suspend fun ScrollScope.snap(from: Float, velocity: Float): Float {
        val projected = decay.calculateTargetValue(from, velocity)
        // Thrown far enough to leave the stretch on its own: that is a fling into the listing.
        if (projected >= range) return decayFling(velocity, stopInRange = false)

        val target = if (projected < range / 2) 0f else range
        if (from == target) return velocity
        var last = from
        animate(from, target, velocity, spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
            scrollBy(value - last)
            last = value
        }
        return 0f
    }
}
