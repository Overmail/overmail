package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.page.home.PREVIEW_ITEMS
import es.jvbabi.overmail.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_stack_action_archive
import overmail.app.shared.generated.resources.home_stack_action_keep
import overmail.app.shared.generated.resources.home_stack_drag
import overmail.app.shared.generated.resources.home_stack_drag_back
import overmail.app.shared.generated.resources.home_stack_drag_held
import overmail.app.shared.generated.resources.home_stack_drag_released
import overmail.app.shared.generated.resources.home_stack_empty
import overmail.app.shared.generated.resources.home_stack_no_subject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.uuid.Uuid

val CARD_PADDING = 16.dp

private val CARD_SHAPE = RoundedCornerShape(16.dp)

/** The card on top and the ones below it that still show; deeper ones are not drawn at all. */
private const val VISIBLE_CARDS = 4

/** How far each card below the top one sits further down. */
private val DEPTH_OFFSET = 10.dp

/** How much smaller each card below the top one is. */
private const val DEPTH_SCALE = 0.02f

/** Share of the card's width a card has to be pulled aside to leave the pile when let go. */
private const val SWIPE_THRESHOLD = 0.3f

/** A flick this fast takes the card with it however little it was pulled. */
private val SWIPE_VELOCITY = 800.dp

/** How far the top card turns when pulled to [SWIPE_THRESHOLD] at the height of its middle, in degrees. */
private const val SWIPE_ROTATION = 16f

/** The most the top card ever turns, however far it is pulled. */
private const val MAX_SWIPE_ROTATION = 30f

/**
 * Where a card turns as if it were held, when it is grabbed in its middle: a little above it, so a
 * card pulled sideways by its centre still tilts the way it does when held by the top.
 */
private const val GRAB_BIAS = 0.5f

/** How long a sheet of the first deal takes to come up into the pile. */
private const val DEAL_DURATION = 750

/** Between two sheets of the first deal, the bottom one first. */
private const val DEAL_STAGGER = 70L

/** Fast off the bottom edge, a hair past the resting spot, settled -- the web app's lay-down. */
private val DEAL_EASING = CubicBezierEasing(0.2f, 1.04f, 0.32f, 1f)

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

    var job: Job? = null
}

/**
 * Where the cards of the pile are while they are being swiped. Hoisted out of [EmailStack]
 * because the gesture is not the stack's own: the listing lies on top of it and takes every
 * touch, so the swipe is picked up around both, see [emailStackSwipe].
 */
@Stable
class EmailStackState internal constructor(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
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

    /** The card being held, from the touch that picked it up until it is let go. */
    private var held: Pair<Email, CardMotion>? by mutableStateOf(null)

    /**
     * The card a touch goes to: the top one of those still on the pile. A card thrown off is
     * already out of the way, so the next swipe does not have to wait for it to be gone.
     */
    private val top: Email? get() = cards.firstOrNull { !isLeaving(it.id) }

    internal val canSwipe: Boolean get() = top != null

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

    internal fun startDrag(position: Offset, slop: Offset) {
        val email = top ?: return
        val motion = motions.getOrPut(email.id) { CardMotion() }
        // Caught on its way back into place: it is taken from where it is.
        motion.job?.cancel()
        motion.grab = grabAt(position)
        held = email to motion
        dragBy(slop)
    }

    internal fun dragBy(delta: Offset) {
        val (_, motion) = held ?: return
        val wasBeyond = abs(motion.offset.x) > threshold
        motion.offset += delta
        // Felt where letting go starts to mean something, and again where it stops to.
        val isBeyond = abs(motion.offset.x) > threshold
        if (isBeyond && !wasBeyond) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        else if (wasBeyond && !isBeyond) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    internal fun release(velocity: Velocity, swipeVelocity: Float) {
        val (email, motion) = held ?: return
        held = null
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
                motions.remove(email.id)
            }
            return
        }

        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
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

@Composable
fun rememberEmailStackState(): EmailStackState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    return remember(scope, haptics) { EmailStackState(scope, haptics) }
}

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
            var dragging = false
            var slop = Offset.Zero

            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id }
                    ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    velocityTracker.addPointerInputChange(change)
                    if (dragging) state.release(velocityTracker.calculateVelocity(), swipeVelocity)
                    return@awaitEachGesture
                }

                velocityTracker.addPointerInputChange(change)
                val delta = change.positionChange()
                // Even while no card can be moved: a touch on the pile never scrolls the listing.
                change.consume()
                if (!state.canSwipe) continue

                if (dragging) {
                    state.dragBy(delta)
                    continue
                }
                slop += delta
                if (slop.getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    state.startDrag(down.position, slop)
                }
            }
            // The pointer went away without being lifted, e.g. a cancelled touch.
            if (dragging) state.release(Velocity.Zero, swipeVelocity)
        }
    }

/**
 * The pile of [emails], the first one on top. Only draws; the swipe comes in through [state], see
 * [emailStackSwipe].
 */
@Composable
fun EmailStack(
    emails: List<Email>,
    isLoading: Boolean,
    state: EmailStackState,
    onSwiped: (Email, StackSwipe) -> Unit,
    contentPaddingValues: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val currentOnSwiped by rememberUpdatedState(onSwiped)
    val visible = emails.filter { it.id !in state.gone }
    val leaving = visible.filter { state.isLeaving(it.id) }
    val cards = visible.filterNot { state.isLeaving(it.id) }.take(VISIBLE_CARDS)

    // The first cards there are come in as a fan pushed up from below; everything after that
    // only moves up the pile. Not state: it is decided once, while composing those cards.
    val firstDeal = remember { FirstDeal() }
    if (firstDeal.ids == null && cards.isNotEmpty()) firstDeal.ids = cards.mapTo(HashSet()) { it.id }

    SideEffect {
        state.cards = visible
        state.onSwiped = { email, swipe -> currentOnSwiped(email, swipe) }
    }

    // Once the list has caught up with a swipe, the mail no longer needs holding back here.
    LaunchedEffect(emails) {
        val ids = emails.mapTo(HashSet()) { it.id }
        state.gone.retainAll(ids)
    }

    Box(
        modifier = modifier
            .padding(contentPaddingValues)
            .padding(CARD_PADDING)
            .onGloballyPositioned {
                state.cardSize = it.size
                state.cardPosition = it.positionInRoot()
            },
    ) {
        if (cards.isEmpty() && leaving.isEmpty()) {
            if (!isLoading) Text(
                text = stringResource(Res.string.home_stack_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        // Back to front, so the top card is drawn last, and the ones on their way out over all of
        // them. One loop for both: a card that is thrown keeps its node, and with it its state.
        for (email in cards.asReversed() + leaving) {
            val depth = cards.indexOf(email).coerceAtLeast(0)
            key(email.id) {
                StackCard(
                    email = email,
                    depth = depth,
                    state = state,
                    dealDelay = if (firstDeal.ids?.contains(email.id) == true) (cards.lastIndex - depth) * DEAL_STAGGER else null,
                )
            }
        }
    }
}

private class FirstDeal {
    var ids: Set<Uuid>? = null
}

/**
 * One sheet of the pile. [dealDelay] is set for a card of the first deal, which comes in from
 * below after that long -- the bottom of the pile is put down first and the top one lands last.
 */
@Composable
private fun StackCard(
    email: Email,
    depth: Int,
    state: EmailStackState,
    dealDelay: Long?,
) {
    // Opaque card under a tint of the background rather than a transparent card: the cards
    // below would show through the ones above them.
    val tint = MaterialTheme.colorScheme.background
    val id = email.id.toString()
    val haptics = LocalHapticFeedback.current

    val dealt = remember { Animatable(if (dealDelay == null) 1f else 0f) }
    // A card that turns up later -- the next one coming into view as the top one leaves -- does
    // not pop up behind the others but comes up out of the pile, from one place further down.
    val appeared = remember { Animatable(if (dealDelay == null) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (dealDelay == null) {
            appeared.animateTo(1f, tween(durationMillis = 400))
            return@LaunchedEffect
        }
        delay(dealDelay)
        dealt.animateTo(1f, tween(DEAL_DURATION, easing = DEAL_EASING))
        // Every sheet is felt landing, one after the other.
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    /** Where the card sits in the pile; a card moves up as the one above it is pulled away. */
    fun place() = (if (depth == 0 || state.isLeaving(email.id)) 0f else depth - state.progress) + (1f - appeared.value)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val place = place()
                // Out of line only below the top: the card you are reading lies straight. A card
                // on its way up straightens out as it goes.
                val loose = place.coerceIn(0f, 1f)
                // Turning around the bottom edge makes the first deal read as one hand opening,
                // and scaling around it keeps the cards below showing at the bottom.
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationX = jitter(id, 2, 10f) * loose * density
                translationY = place * DEPTH_OFFSET.toPx() + jitter(id, 3, 6f) * loose * density
                rotationZ = jitter(id, 1, 2.5f) * loose
                scaleX = 1f - place * DEPTH_SCALE + jitter(id, 4, 0.01f) * loose
                scaleY = scaleX

                val motion = state.motionOf(email.id)
                if (motion != null) {
                    translationX += motion.offset.x
                    translationY += motion.offset.y
                    // Held, the card turns around its middle, not around its bottom edge.
                    transformOrigin = TransformOrigin.Center
                    rotationZ += state.rotationOf(motion)
                }

                alpha = appeared.value

                val out = 1f - dealt.value
                if (out != 0f) {
                    // Past the full height of the pile, so every sheet starts out of sight.
                    translationY += out * (size.height * 1.3f + depth * DEPTH_OFFSET.toPx() + jitter(id, 6, 5f) * density)
                    // Every other sheet to the other side, so the fan opens around the middle,
                    // and wider towards the back.
                    rotationZ += out * ((if (depth % 2 == 0) -1 else 1) * (7f + depth * 4f) + jitter(id, 7, 2f))
                }
            }
            .dropShadow(CARD_SHAPE, shadow = Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.12f), offset = DpOffset(0.dp, 8.dp)))
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .drawWithContent {
                drawContent()
                val place = place()
                if (place > 0f) drawRect(tint, alpha = (0.05f + place * 0.15f).coerceAtMost(0.65f))
            }
            .padding(24.dp),
    ) {
        Text(
            text = email.subject ?: stringResource(Res.string.home_stack_no_subject),
            style = MaterialTheme.typography.headlineSmall,
            color = if (email.subject == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )

        state.dragOf(email.id)?.let { drag ->
            DragLabel(drag = drag, modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}

/** What the hand on a card is doing, spelled out -- for now, until the card shows it properly. */
@Composable
private fun DragLabel(drag: CardDrag, modifier: Modifier = Modifier) {
    val hold = stringResource(if (drag.isHeld) Res.string.home_stack_drag_held else Res.string.home_stack_drag_released)
    val towards = drag.towards?.let { stringResource(it.label()) } ?: "–"
    val action = drag.action?.let { stringResource(it.label()) } ?: stringResource(Res.string.home_stack_drag_back)
    Text(
        text = stringResource(Res.string.home_stack_drag, hold, towards, (drag.progress * 100).roundToInt(), action),
        style = MaterialTheme.typography.bodyMedium,
        color = if (drag.action == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

private fun StackSwipe.label() = when (this) {
    StackSwipe.Archive -> Res.string.home_stack_action_archive
    StackSwipe.Keep -> Res.string.home_stack_action_keep
}

/**
 * A value in [-spread, spread] that looks random but is the same for a mail every time: a hash of
 * its [id], like the web app's `jitter`, so a card keeps its skew while the pile is worked through.
 */
private fun jitter(id: String, salt: Int, spread: Float): Float {
    var hash = 2166136261u xor salt.toUInt()
    for (char in id) hash = (hash xor char.code.toUInt()) * 16777619u
    return (hash.toFloat() / UInt.MAX_VALUE.toFloat() * 2f - 1f) * spread
}

@Composable
@Preview
private fun EmailStackPreview() {
    AppTheme(dynamicColor = false) {
        val state = rememberEmailStackState()
        EmailStack(
            emails = PREVIEW_ITEMS.map { it.email },
            isLoading = false,
            state = state,
            onSwiped = { _, _ -> },
            contentPaddingValues = PaddingValues(),
            modifier = Modifier
                .fillMaxSize()
                .emailStackSwipe(state) { true },
        )
    }
}
