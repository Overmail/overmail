package es.jvbabi.overmail.ui.lift

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import es.jvbabi.overmail.ui.components.ScrollOffset
import kotlin.math.roundToInt

/** How far lifted content stays from the edges of the host, on top of the system bars. */
private val LIFTED_PADDING = 16.dp

/** How much everything behind lifted content is blurred, once it is all the way up. */
private val LIFTED_BLUR = 24.dp

/** How dark everything behind lifted content gets, once it is all the way up. */
private const val LIFTED_SCRIM = 0.2f

/**
 * Where content of [state] is lifted to, and what it is lifted over: goes on the container of
 * everything the content should lie over, usually the whole screen. Blurs and darkens that as far
 * as the content is up and draws the lifted content on top, grown to the host less the system bars
 * and [padding].
 */
@Composable
fun Modifier.liftHost(state: LiftState, padding: Dp = LIFTED_PADDING): Modifier {
    val insets = WindowInsets.systemBars
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    SideEffect {
        state.hostInsets = insets
        state.hostPadding = padding
        state.hostDensity = density
        state.hostLayoutDirection = layoutDirection
    }
    return this
        .onGloballyPositioned { state.hostBounds = Rect(it.positionInRoot(), it.size.toSize()) }
        .drawWithContent {
            drawContent()
            val progress = state.progress
            if (progress > 0f) drawRect(Color.Black, alpha = LIFTED_SCRIM * progress)
            val key = state.lifted ?: return@drawWithContent
            val target = state.targets[key] ?: return@drawWithContent
            val layer = target.layer ?: return@drawWithContent
            // The content is laid out at its lifted size throughout; on the way it is only
            // scaled to the width it has come to and cut off at the height.
            val (bounds, scale, shown) = state.drawnCut(key, this, layoutDirection) ?: return@drawWithContent
            val clip = Path().apply { addOutline(target.shape.createOutline(shown, layoutDirection, this@drawWithContent)) }
            val host = state.hostBounds
            translate(bounds.left - host.left, bounds.top - host.top) {
                scale(scale, pivot = Offset.Zero) {
                    clipPath(clip) { drawLayer(layer) }
                }
            }
        }
        // Only what is below the lifted content: that is drawn above, outside this layer.
        .graphicsLayer {
            val radius = LIFTED_BLUR.toPx() * state.progress
            renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
        }
}

/**
 * Makes this content liftable as [key] of [state], see [LiftState.lift]. It keeps its place in the
 * layout -- what is around it does not move -- but while it is lifted, it is laid out at the size
 * it is lifted to and drawn by the [liftHost] rather than here: grown out of its place by scaling
 * it and cutting it off in [shape], never by laying it out again. It stays the same node all the
 * while, so nothing in it is composed or created again, and it is measured twice per lift, up and
 * down, not on every frame of it. [scroll] is what the finger scrolls while it is lifted.
 *
 * Reading [LiftState.isLifted] inside is how content can look different while lifted.
 */
@Composable
fun Modifier.liftable(state: LiftState, key: Any, scroll: ScrollOffset? = null, shape: Shape = RectangleShape): Modifier =
    liftTarget(state, key, scroll, shape, origin = null)

/**
 * Makes this content what is lifted as [key] of [state], out of [origin] rather than a place of its
 * own: it takes no room and is not drawn until it is lifted. For content that stands in for
 * something else while lifted -- a row of a list lifting a preview of what it stands for -- and
 * stays composed in between, so what is expensive in it is created once, not per lift.
 *
 * It is always laid out at the size it is lifted to, but only placed while it is lifted, so it
 * takes no touch in between. [origin] is read while it is lifted, in the root; otherwise like
 * [liftable].
 */
@Composable
fun Modifier.liftStandIn(state: LiftState, key: Any, origin: () -> Rect, scroll: ScrollOffset? = null, shape: Shape = RectangleShape): Modifier =
    liftTarget(state, key, scroll, shape, origin)

@Composable
private fun Modifier.liftTarget(state: LiftState, key: Any, scroll: ScrollOffset?, shape: Shape, origin: (() -> Rect)?): Modifier {
    val layer = rememberGraphicsLayer()
    val target = remember(state, key, layer) { LiftTarget(layer) }
    SideEffect {
        target.scroll = scroll
        target.shape = shape
        target.origin = origin
    }
    DisposableEffect(state, key, target) {
        state.targets[key] = target
        onDispose { if (state.targets[key] === target) state.targets.remove(key) }
    }
    return this
        .onGloballyPositioned { target.resting = Rect(it.positionInRoot(), it.size.toSize()) }
        .layout { measurable, constraints ->
            if (origin == null && !state.isLifted(key)) {
                val placeable = measurable.measure(constraints)
                return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            val to = state.targetBounds(this, layoutDirection)
            val placeable = measurable.measure(
                Constraints.fixed(to.width.roundToInt().coerceAtLeast(0), to.height.roundToInt().coerceAtLeast(0))
            )
            // A stand-in takes no room; lifted content keeps the room it had.
            val width = if (origin == null) constraints.constrainWidth(target.resting.width.roundToInt()) else 0
            val height = if (origin == null) constraints.constrainHeight(target.resting.height.roundToInt()) else 0
            layout(width, height) {
                // A stand-in that is not lifted is not placed at all: placed, it would lie over the
                // host unseen and take every touch -- a web view in it does, for one.
                if (origin == null || state.isLifted(key)) placeable.place(0, 0)
            }
        }
        .drawWithContent {
            // Always into the layer, so the host has it the moment the content is lifted.
            layer.record { this@drawWithContent.drawContent() }
            if (origin == null && !state.isLifted(key)) drawLayer(layer)
        }
}

/**
 * Lifts this content as [key] of [state] once a finger has rested on it for a long press, scrolls
 * it while the finger moves and puts it down when the finger lets go. A finger that moves before
 * that is left to whatever else wants it, a list that scrolls for instance. Once lifted, every
 * change of the finger is consumed, its release too, so put this inside a `clickable` to keep the
 * long press from also being a click.
 *
 * For content that decides by itself when it is lifted, like a pile of cards that is also swiped,
 * call [LiftState.lift], [LiftState.moveFinger] and [LiftState.release] instead.
 */
fun Modifier.liftOnLongPress(state: LiftState, key: Any): Modifier = pointerInput(state, key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        state.lift(key)
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == longPress.id } ?: break
                val up = change.changedToUpIgnoreConsumed()
                if (!up) state.moveFinger(change.positionChange().y)
                // Up included: the finger that lifted the content does not also tap what is below it.
                change.consume()
                if (up) break
            }
        } finally {
            state.release()
        }
    }
}
