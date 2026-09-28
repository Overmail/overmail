package es.jvbabi.overmail.ui.lift

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.ui.components.ScrollOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** How far the finger can move from where it lifted the content before the content starts to scroll. */
private val SCROLL_DEADZONE = 8.dp

/** How fast the content scrolls per px the finger is past the dead zone, squared, per second. */
internal const val SCROLL_SPEED = 6f

/**
 * What can be lifted off the screen and what is lifted: one content at a time, picked by the key
 * it was made [liftable] with, grown from its place to as much of the [liftHost] as there is and
 * drawn over everything in it. The content is not composed again for that -- the node that is
 * already on screen is laid out at its lifted size and drawn by the host instead of in its place,
 * grown by scaling and cutting it off rather than by laying it out on every frame -- so nothing
 * in it is created anew or rendered again by lifting or putting it down.
 *
 * Whoever holds the finger drives it: [lift], then [moveFinger] for every move, [release] when it
 * lets go. Moved up or down, the finger scrolls the content like a joystick: the further from where
 * it lifted it, past a small dead zone, the faster -- down reads on.
 */
@Stable
class LiftState internal constructor(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback?,
    /** In px. */
    private val scrollDeadzone: Float,
) {
    /** The key of what is lifted, from [lift] until it is back down after [release]. */
    var lifted: Any? by mutableStateOf(null)
        private set

    private val progressAnimatable = Animatable(0f)

    /** How far [lifted] is on its way up: 0 in its place, 1 over everything. */
    val progress: Float get() = progressAnimatable.value

    /** A finger holds [lifted] up, rather than it being on its way down. */
    var isHeld by mutableStateOf(false)
        private set

    /** How far the finger has moved up or down since it lifted the content, in px. */
    var fingerY by mutableFloatStateOf(0f)
        private set

    private var scrollJob: Job? = null

    internal val targets = mutableStateMapOf<Any, LiftTarget>()

    /** Where the [liftHost] is in the root, which is what lifted content grows to fill. */
    internal var hostBounds by mutableStateOf(Rect.Zero)
    internal var hostInsets: WindowInsets? = null
    internal var hostPadding: Dp = 0.dp
    internal var hostDensity: Density? = null
    internal var hostLayoutDirection: LayoutDirection = LayoutDirection.Ltr

    fun isLifted(key: Any): Boolean = lifted == key

    /**
     * Lifts the content made [liftable] with [key] and starts scrolling it by the finger. What was
     * lifted before is dropped back into its place at once.
     */
    fun lift(key: Any) {
        scrollJob?.cancel()
        fingerY = 0f
        targets[key]?.scroll?.scrollTo(0f)
        lifted = key
        isHeld = true
        haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { progressAnimatable.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
        scrollJob = scope.launch {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val velocity = liftScrollVelocity(fingerY, scrollDeadzone)
                if (velocity != 0f) targets[key]?.scroll?.scrollBy(velocity * (now - last) / 1_000_000_000f)
                last = now
            }
        }
    }

    /** The finger holding the lifted content moved by [deltaY] px. */
    fun moveFinger(deltaY: Float) {
        if (!isHeld) return
        fingerY += deltaY
    }

    /** The finger let go: the content goes back into its place, scrolled back to its top on the way. */
    fun release() {
        val key = lifted ?: return
        if (!isHeld) return
        isHeld = false
        scrollJob?.cancel()
        fingerY = 0f
        scope.launch {
            // Back to its top on the way down: what it turns back into in its place is there.
            targets[key]?.scroll?.let { scroll ->
                launch { animate(scroll.value, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> scroll.scrollTo(value) } }
            }
            progressAnimatable.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
            // Not if something else was lifted meanwhile, which takes the animation over.
            if (lifted == key) lifted = null
        }
    }

    /**
     * How much of [key] shows from its top while it is lifted, in its own px: it is laid out at
     * its lifted size throughout and cut off there on the way. Null while it is not lifted, or not
     * yet under a [liftHost]. Read while drawing, it follows every frame without anything being
     * composed or laid out again -- for what belongs at the edge that cuts it off.
     */
    fun shownHeight(key: Any): Float? {
        if (!isLifted(key)) return null
        val density = hostDensity ?: return null
        return drawnCut(key, density, hostLayoutDirection)?.shown?.height
    }

    /** Where [key] is drawn while lifted, and how, see [liftedCut]; null if there is nothing to draw. */
    internal fun drawnCut(key: Any, density: Density, layoutDirection: LayoutDirection): LiftedCut? {
        val target = targets[key] ?: return null
        val to = targetBounds(density, layoutDirection)
        val bounds = liftedBounds(target.origin?.invoke() ?: target.resting, to, progress)
        if (to.width <= 0f || bounds.width <= 0f) return null
        return liftedCut(to, bounds)
    }

    /** Where lifted content ends up, in the root: the host, less the system bars and the padding. */
    internal fun targetBounds(density: Density, layoutDirection: LayoutDirection): Rect {
        val insets = hostInsets
        val padding = with(density) { hostPadding.toPx() }
        return liftTarget(
            host = hostBounds,
            left = padding + (insets?.getLeft(density, layoutDirection) ?: 0),
            top = padding + (insets?.getTop(density) ?: 0),
            right = padding + (insets?.getRight(density, layoutDirection) ?: 0),
            bottom = padding + (insets?.getBottom(density) ?: 0),
        )
    }
}

/**
 * One piece of content that can be lifted, as [liftable] registers it with the [LiftState]: what
 * it is drawn into, which the host draws while it is lifted, and what the finger scrolls.
 */
@Stable
internal class LiftTarget(val layer: GraphicsLayer?) {
    var scroll: ScrollOffset? = null

    /** Where it lies when it is not lifted, in the root. */
    var resting: Rect = Rect.Zero

    /** Where it grows out of instead of [resting], for a stand-in; see [liftStandIn]. */
    var origin: (() -> Rect)? = null

    /** What it is cut off in while it grows. */
    var shape: Shape = RectangleShape
}

@Composable
fun rememberLiftState(): LiftState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    return remember(scope, haptics, density) { LiftState(scope, haptics, with(density) { SCROLL_DEADZONE.toPx() }) }
}

/**
 * How fast lifted content scrolls with the finger [fingerY] px from where it lifted it, in px per
 * second: not at all within [deadzone], past it in proportion to how far, down for a finger below.
 */
internal fun liftScrollVelocity(fingerY: Float, deadzone: Float, speed: Float = SCROLL_SPEED): Float {
    val past = abs(fingerY) - deadzone
    return if (past > 0f) sign(fingerY) * past * speed * speed else 0f
}

/** [host] with [left], [top], [right] and [bottom] px taken off its edges. */
internal fun liftTarget(host: Rect, left: Float, top: Float, right: Float, bottom: Float): Rect = Rect(
    left = host.left + left,
    top = host.top + top,
    right = host.right - right,
    bottom = host.bottom - bottom,
)

/** Where content [progress] of the way from [resting] to [target] is: 0 in its place, 1 lifted. */
internal fun liftedBounds(resting: Rect, target: Rect, progress: Float): Rect = lerp(resting, target, progress)

/** How lifted content is drawn on its way: at [bounds], scaled by [scale], [shown] of it from its top in its own px. */
internal data class LiftedCut(val bounds: Rect, val scale: Float, val shown: Size)

/**
 * How content laid out at [target] is drawn to fill [bounds] on its way there: the scale that
 * brings it to that width, and how much of it, in its own px, that leaves showing from its top.
 */
internal fun liftedCut(target: Rect, bounds: Rect): LiftedCut {
    val scale = bounds.width / target.width
    return LiftedCut(bounds, scale, Size(target.width, bounds.height / scale))
}
