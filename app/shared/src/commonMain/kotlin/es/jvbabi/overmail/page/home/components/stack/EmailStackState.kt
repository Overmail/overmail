package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.ui.lift.LiftState
import es.jvbabi.overmail.ui.lift.rememberLiftState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** Share of the card's width a card has to be pulled aside to leave the pile when let go. */
private const val SWIPE_THRESHOLD = 0.3f

/**
 * How long a card has to be held past the threshold for letting go to be felt: a card only
 * flicked past it was not a decision the hand made there.
 */
private val CONFIRM_AFTER = 50.milliseconds

/** How long a finger has to rest on a card, unmoved, for the card to be lifted off the pile. */
internal val LIFT_AFTER = 200.milliseconds

/** How big the top card is while a finger is on it. */
private const val PRESSED_SCALE = 0.96f

/** How far the top card turns when pulled to [SWIPE_THRESHOLD] at the height of its middle, in degrees. */
private const val SWIPE_ROTATION = 16f

/** The most the top card ever turns, however far it is pulled. */
private const val MAX_SWIPE_ROTATION = 30f

/**
 * Where a card turns as if it were held, when it is grabbed in its middle: a little above it, so a
 * card pulled sideways by its centre still tilts the way it does when held by the top.
 */
private const val GRAB_BIAS = 0.5f

/** What a card knows about the hand on it, see [EmailStackState.dragOf]. */
data class CardDrag(
    /** A finger is on it, rather than it springing back or flying off. */
    val isHeld: Boolean,
    /** The action it is pulled towards, by the side it is pulled to; null while straight. */
    val towards: StackSwipe?,
    /**
     * How far it is pulled towards that action, as a share of the way to where letting go carries
     * it off: 1 at the threshold, more past it.
     */
    val progress: Float,
    /** What letting go now does, or did for a card on its way out; null for back onto the pile. */
    val action: StackSwipe?,
)

enum class StackSwipe {
    /** To the left. */
    Archive,

    /** To the right. */
    Keep,
}

/**
 * How one card is moved by hand, and where it goes once it is let go. Every card has its own, so
 * a card thrown off the pile finishes its way out while the next one is already being swiped.
 */
@Stable
internal class CardMotion {
    /** How far the card is pulled away from its place, in px. */
    var offset by mutableStateOf(Offset.Zero)

    /** Where the card is held, from its centre, in halves of its size: -1 to 1 either way. */
    var grab by mutableStateOf(Offset.Zero)

    /** Thrown off the pile and on its way out: no touch goes to it any more. */
    var leaving by mutableStateOf(false)

    /** What it was thrown off for, once it is [leaving]. */
    var thrownFor: StackSwipe? by mutableStateOf(null)

    /** How big it is while a finger is on it: a little smaller, as if pressed into the pile. */
    val press = Animatable(1f)

    var job: Job? = null
}

/**
 * Where the cards of the pile are while they are being swiped. Hoisted out of [EmailStack]
 * because the gesture is not the stack's own: it is picked up by [emailStackSwipe], which the
 * screen puts where it wants the pile to take touches.
 */
@Stable
class EmailStackState internal constructor(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    /** What a card held still is lifted off the pile by, to be read over everything else. */
    val lift: LiftState,
) {
    /** Only the cards that are moved or on their way somewhere; the rest lie in their place. */
    private val motions = mutableStateMapOf<Uuid, CardMotion>()

    internal var cardSize by mutableStateOf(IntSize.Zero)

    /** Where the card is in the root, so a touch can be told where on the card it is. */
    internal var cardPosition = Offset.Zero

    /** Where the node taking the touches is in the root, see [emailStackSwipe]. */
    internal var touchOrigin = Offset.Zero

    /**
     * Swiped off here but maybe still in the list that came in: the card must not be drawn again
     * in the frames between the end of its exit and the list without it.
     */
    internal val gone = mutableStateSetOf<Uuid>()

    /** What the pile shows, top first, the cards on their way out included. */
    internal var cards: List<Email> by mutableStateOf(emptyList())
    internal var onSwiped: (Email, StackSwipe) -> Unit = { _, _ -> }
    internal var onOpen: (Email) -> Unit = {}

    /** The card being held, from the touch that picked it up until it is let go. */
    private var held: Pair<Email, CardMotion>? by mutableStateOf(null)

    /** Since when the held card has been past the threshold, without going back; null while it is not. */
    private var beyondSince: TimeMark? = null

    /** Lifts the held card once it has been held still for [LIFT_AFTER]. */
    private var liftJob: Job? = null

    /**
     * The card a touch goes to: the top one of those still on the pile. A card thrown off is
     * already out of the way, so the next swipe does not have to wait for it to be gone.
     */
    private val top: Email? get() = cards.firstOrNull { !isLeaving(it.id) }

    private val threshold: Float get() = cardSize.width * SWIPE_THRESHOLD

    internal fun motionOf(id: Uuid): CardMotion? = motions[id]

    internal fun isLeaving(id: Uuid): Boolean = motions[id]?.leaving == true

    /** How the card of [id] is being moved, or null while it lies in its place. */
    internal fun dragOf(id: Uuid): CardDrag? {
        val motion = motions[id] ?: return null
        val x = motion.offset.x
        val progress = if (cardSize.width == 0) 0f else abs(x) / threshold
        return CardDrag(
            isHeld = held?.second === motion,
            towards = when {
                x < 0f -> StackSwipe.Archive
                x > 0f -> StackSwipe.Keep
                else -> null
            },
            progress = progress,
            action = when {
                motion.leaving -> motion.thrownFor
                progress < 1f -> null
                x < 0f -> StackSwipe.Archive
                else -> StackSwipe.Keep
            },
        )
    }

    /** Share of the way to [SWIPE_THRESHOLD] the top card on the pile has been pulled, 0 to 1. */
    internal val progress: Float
        get() {
            val motion = top?.let { motions[it.id] } ?: return 0f
            return if (cardSize.width == 0) 0f else (abs(motion.offset.x) / threshold).coerceAtMost(1f)
        }

    /**
     * How far a card is turned, in degrees. It turns the way a sheet held at [CardMotion.grab]
     * does when pulled by [CardMotion.offset]: held above its middle and pulled right, it turns
     * clockwise; held on the right and pulled down, too.
     */
    internal fun rotationOf(motion: CardMotion): Float {
        if (cardSize.width == 0) return 0f
        val torque = motion.grab.x * motion.offset.y - (motion.grab.y - GRAB_BIAS) * motion.offset.x
        return (torque / threshold * SWIPE_ROTATION).coerceIn(-MAX_SWIPE_ROTATION, MAX_SWIPE_ROTATION)
    }

    private fun grabAt(position: Offset): Offset {
        val halfSize = Offset(cardSize.width / 2f, cardSize.height / 2f)
        if (halfSize.x == 0f || halfSize.y == 0f) return Offset.Zero
        val fromCentre = touchOrigin + position - cardPosition - halfSize
        return Offset(
            x = (fromCentre.x / halfSize.x).coerceIn(-1f, 1f),
            y = (fromCentre.y / halfSize.y).coerceIn(-1f, 1f),
        )
    }

    /**
     * A finger came down on the top card: it is held from here on, whether it is moved or only
     * touched. False when there is no card to hold.
     */
    internal fun press(position: Offset): Boolean {
        val email = top ?: return false
        val motion = motions.getOrPut(email.id) { CardMotion() }
        // Caught on its way back into place: it is taken from where it is.
        motion.job?.cancel()
        motion.grab = grabAt(position)
        held = email to motion
        beyondSince = null
        liftJob?.cancel()
        liftJob = scope.launch {
            delay(LIFT_AFTER)
            lift.lift(email.id)
        }
        scope.launch { motion.press.animateTo(PRESSED_SCALE, spring(stiffness = Spring.StiffnessMedium)) }
        return true
    }

    /** The top card was tapped twice: its mail is opened on its page, which grows out of the card. */
    internal fun open() {
        top?.let(onOpen)
    }

    internal fun dragBy(delta: Offset) {
        val (email, motion) = held ?: return
        // A lifted card is there to be read, not swiped: it stays where it is until it is let go,
        // and the finger scrolls through it instead.
        if (lift.isLifted(email.id)) {
            lift.moveFinger(delta.y)
            return
        }
        // Moved before it was lifted: a swipe, not a card to read.
        liftJob?.cancel()
        val wasBeyond = abs(motion.offset.x) > threshold
        motion.offset += delta
        // Felt where letting go starts to mean something, and again where it stops to.
        val isBeyond = abs(motion.offset.x) > threshold
        if (isBeyond && !wasBeyond) {
            beyondSince = TimeSource.Monotonic.markNow()
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        } else if (wasBeyond && !isBeyond) {
            beyondSince = null
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    internal fun release(velocity: Velocity, swipeVelocity: Float) {
        val (email, motion) = held ?: return
        held = null
        liftJob?.cancel()
        if (lift.isLifted(email.id)) lift.release()
        val unpress = scope.launch { motion.press.animateTo(1f, spring(stiffness = Spring.StiffnessMedium)) }
        val offset = motion.offset
        // A flick only counts in the direction the card is already pulled, so a card dragged
        // right and flicked back left returns instead of switching sides.
        val swipe = when {
            offset.x > threshold || (offset.x > 0 && velocity.x > swipeVelocity) -> StackSwipe.Keep
            offset.x < -threshold || (offset.x < 0 && velocity.x < -swipeVelocity) -> StackSwipe.Archive
            else -> null
        }
        val initialVelocity = Offset(velocity.x, velocity.y)

        if (swipe == null) {
            motion.job = scope.launch {
                animate(Offset.VectorConverter, offset, Offset.Zero, initialVelocity, spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> motion.offset = value }
                // A card only touched is back in place at once, but still growing back.
                unpress.join()
                motions.remove(email.id)
            }
            return
        }

        val heldPast = beyondSince?.let { it.elapsedNow() >= CONFIRM_AFTER } == true
        beyondSince = null
        if (heldPast) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        // Right away rather than once it is out: the next touch already goes to the card below.
        motion.leaving = true
        motion.thrownFor = swipe
        motion.job = scope.launch {
            // On along the way it was thrown, up or down included.
            val target = Offset(
                x = offset.x.sign * cardSize.width * 1.5f,
                y = offset.y + velocity.y * 0.15f,
            )
            animate(Offset.VectorConverter, offset, target, initialVelocity, tween(durationMillis = 250)) { value, _ -> motion.offset = value }
            gone += email.id
            motions.remove(email.id)
            onSwiped(email, swipe)
        }
    }
}

/** [lift] is what the card is lifted with; share it with whatever else on the screen lifts. */
@Composable
fun rememberEmailStackState(lift: LiftState = rememberLiftState()): EmailStackState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    return remember(scope, haptics, lift) { EmailStackState(scope, haptics, lift) }
}
