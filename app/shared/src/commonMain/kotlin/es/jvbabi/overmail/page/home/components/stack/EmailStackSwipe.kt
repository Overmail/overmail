package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/** A flick this fast takes the card with it however little it was pulled. */
private val SWIPE_VELOCITY = 800.dp

/**
 * Picks up the swipe on the top card of [state]. Goes on a parent of both the stack and whatever
 * lies on top of it: a touch that starts where [accepts] says the pile is, in this node's
 * coordinates, is the card's alone -- it is taken before the children see it, so the listing on
 * top does not scroll from there -- and moves the card whichever way it goes. Everywhere else the
 * children get the touch as usual.
 *
 * Remember [accepts]: a new one restarts the detection, and with it a drag in progress.
 */
fun Modifier.emailStackSwipe(
    state: EmailStackState,
    accepts: (Offset) -> Boolean,
): Modifier = this
    .onGloballyPositioned { state.touchOrigin = it.positionInRoot() }
    .pointerInput(state) {
        val swipeVelocity = SWIPE_VELOCITY.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!accepts(down.position)) return@awaitEachGesture

            val velocityTracker = VelocityTracker()
            velocityTracker.addPointerInputChange(down)
            // Held right away, so the card gives under the finger before it is moved at all.
            val pressed = state.press(down.position)
            var dragging = false
            var slop = Offset.Zero

            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id }
                    ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    velocityTracker.addPointerInputChange(change)
                    if (pressed) state.release(if (dragging) velocityTracker.calculateVelocity() else Velocity.Zero, swipeVelocity)
                    return@awaitEachGesture
                }

                velocityTracker.addPointerInputChange(change)
                val delta = change.positionChange()
                // Even while no card can be moved: a touch on the pile never scrolls the listing.
                change.consume()
                if (!pressed) continue

                if (dragging) {
                    state.dragBy(delta)
                    continue
                }
                slop += delta
                if (slop.getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    state.dragBy(slop)
                }
            }
            // The pointer went away without being lifted, e.g. a cancelled touch.
            if (pressed) state.release(Velocity.Zero, swipeVelocity)
        }
    }
