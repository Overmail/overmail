package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.uuid.Uuid

/** How far a lifted card stays from the edges of the screen, on top of the system bars. */
private val LIFTED_PADDING = 16.dp

/** How much everything behind a lifted card is blurred, once it is all the way up. */
private val LIFTED_BLUR = 24.dp

/** How dark everything behind a lifted card gets, once it is all the way up. */
private const val LIFTED_SCRIM = 0.2f

/** How far the finger can move from where it lifted the card before the mail starts to scroll. */
private val SCROLL_DEADZONE = 16.dp

/** How fast the mail scrolls per px the finger is past [SCROLL_DEADZONE], in px per second. */
private const val SCROLL_SPEED = 8f

/**
 * The card of [state] that is lifted off the pile, laid over everything else and grown from its
 * place in the pile to as much of the screen as [LIFTED_PADDING] and the system bars leave. Goes
 * over the whole screen, above whatever lies on top of the pile; everything else goes under
 * [blurredBehindLiftedCard].
 *
 * It takes no touch of its own: the finger that lifted it is still the pile's, see
 * [emailStackSwipe]. It does not move the card any more -- a lifted card is read, not swiped --
 * and lays it back down when it lets go. Moved up or down, it scrolls the mail like a joystick:
 * the further from where it lifted the card, past a small dead zone, the faster -- down reads on.
 */
@Composable
fun LiftedCard(
    state: EmailStackState,
    bodies: Map<Uuid, StackCardBody>,
    modifier: Modifier = Modifier,
) {
    val insets = WindowInsets.systemBars
    val density = LocalDensity.current
    // Where this box is in the root, to find the pile in it: the card's place there is in the root.
    val origin = remember { OffsetHolder() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { origin.value = it.positionInRoot() }
            .drawBehind { drawRect(Color.Black, alpha = LIFTED_SCRIM * state.lift.value) },
    ) {
        val email = state.lifted ?: return@Box

        LaunchedEffect(email) {
            val deadzone = with(density) { SCROLL_DEADZONE.toPx() }
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val seconds = (now - last) / 1_000_000_000f
                last = now
                val finger = state.liftedFingerY
                val past = abs(finger) - deadzone
                if (past > 0f) state.liftedScroll.scrollBy(sign(finger) * past * SCROLL_SPEED * seconds)
            }
        }

        StackCard(
            email = email,
            body = bodies[email.id] ?: StackCardBody.Loading,
            depth = 0,
            pose = {
                val motion = state.motionOf(email.id)
                CardPose(hand = motion?.let { CardHand(offset = it.offset, rotation = state.rotationOf(it), press = it.press.value) })
            },
            drag = state.dragOf(email.id),
            scroll = state.liftedScroll,
            modifier = Modifier.layout { measurable, constraints ->
                val inPile = Rect(state.cardPosition - origin.value, state.cardSize.toSize())
                val padding = LIFTED_PADDING.roundToPx()
                val onScreen = Rect(
                    left = (padding + insets.getLeft(this, layoutDirection)).toFloat(),
                    top = (padding + insets.getTop(this)).toFloat(),
                    right = (constraints.maxWidth - padding - insets.getRight(this, layoutDirection)).toFloat(),
                    bottom = (constraints.maxHeight - padding - insets.getBottom(this)).toFloat(),
                )
                // Read here, in layout, so the card is moved and grown frame by frame without
                // anything around it being composed again.
                val bounds = lerp(inPile, onScreen, state.lift.value)
                val placeable = measurable.measure(
                    Constraints.fixed(bounds.width.roundToInt().coerceAtLeast(0), bounds.height.roundToInt().coerceAtLeast(0))
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                }
            },
        )
    }
}

/**
 * Blurs what it is put on as far as a card of [state] is lifted: what lies behind the lifted card.
 * Read while drawing, so the lift does not compose anything again.
 */
fun Modifier.blurredBehindLiftedCard(state: EmailStackState): Modifier = graphicsLayer {
    val radius = LIFTED_BLUR.toPx() * state.lift.value
    renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
}

private class OffsetHolder {
    var value = Offset.Zero
}
