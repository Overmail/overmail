package es.jvbabi.overmail.ui.lift

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
    SideEffect {
        state.hostInsets = insets
        state.hostPadding = padding
    }
    return this
        .onGloballyPositioned { state.hostBounds = Rect(it.positionInRoot(), it.size.toSize()) }
        .drawWithContent {
            drawContent()
            val progress = state.progress
            if (progress > 0f) drawRect(Color.Black, alpha = LIFTED_SCRIM * progress)
            val target = state.lifted?.let { state.targets[it] } ?: return@drawWithContent
            val host = state.hostBounds
            translate(target.bounds.left - host.left, target.bounds.top - host.top) { target.layer?.let { drawLayer(it) } }
        }
        // Only what is below the lifted content: that is drawn above, outside this layer.
        .graphicsLayer {
            val radius = LIFTED_BLUR.toPx() * state.progress
            renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
        }
}

/**
 * Makes this content liftable as [key] of [state], see [LiftState.lift]. It keeps its place in the
 * layout -- what is around it does not move -- but while it is lifted, it is laid out as big as
 * the lift has grown it and drawn by the [liftHost] rather than here. It stays the same node all
 * the while, so nothing in it is composed or created again. [scroll] is what the finger scrolls
 * while it is lifted.
 *
 * Reading [LiftState.isLifted] inside is how content can look different while lifted.
 */
@Composable
fun Modifier.liftable(state: LiftState, key: Any, scroll: ScrollOffset? = null): Modifier {
    val layer = rememberGraphicsLayer()
    val target = remember(state, key, layer) { LiftTarget(layer) }
    SideEffect { target.scroll = scroll }
    DisposableEffect(state, key, target) {
        state.targets[key] = target
        onDispose { if (state.targets[key] === target) state.targets.remove(key) }
    }
    return this
        .onGloballyPositioned { target.resting = Rect(it.positionInRoot(), it.size.toSize()) }
        .layout { measurable, constraints ->
            if (!state.isLifted(key)) {
                val placeable = measurable.measure(constraints)
                return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            val resting = target.resting
            // Read here, in layout, so the content is moved and grown frame by frame without
            // anything around it being composed again.
            val bounds = liftedBounds(resting, state.targetBounds(this, layoutDirection), state.progress)
            target.bounds = bounds
            val placeable = measurable.measure(
                Constraints.fixed(bounds.width.roundToInt().coerceAtLeast(0), bounds.height.roundToInt().coerceAtLeast(0))
            )
            // Its place stays as big as it was; only what is in it grows.
            val width = constraints.constrainWidth(resting.width.roundToInt())
            val height = constraints.constrainHeight(resting.height.roundToInt())
            layout(width, height) {
                placeable.place((bounds.left - resting.left).roundToInt(), (bounds.top - resting.top).roundToInt())
            }
        }
        .drawWithContent {
            // Always into the layer, so the host has it the moment the content is lifted.
            layer.record { this@drawWithContent.drawContent() }
            if (!state.isLifted(key)) drawLayer(layer)
        }
}

/**
 * Lifts this content as [key] of [state] once a finger has rested on it for a long press, scrolls
 * it while the finger moves and puts it down when the finger lets go. A finger that moves before
 * that is left to whatever else wants it, a list that scrolls for instance.
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
            drag(longPress.id) { change ->
                state.moveFinger(change.positionChange().y)
                change.consume()
            }
        } finally {
            state.release()
        }
    }
}
