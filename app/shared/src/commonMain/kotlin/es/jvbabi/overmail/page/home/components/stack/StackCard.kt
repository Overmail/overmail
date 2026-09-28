package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.ui.components.ScrollOffset

private val CARD_SHAPE = RoundedCornerShape(16.dp)

/** How far each card below the top one sits further down. */
private val DEPTH_OFFSET = 10.dp

/** How much smaller each card below the top one is. */
private const val DEPTH_SCALE = 0.02f

/**
 * Where a card is drawn, relative to where the pile puts it: everything that moves a card, in one
 * value. [StackCard] reads it while drawing, so a card that is swiped is redrawn, not recomposed.
 */
internal data class CardPose(
    /** How far down the pile it lies: 0 on top, 1 one card below, fractions on the way up. */
    val place: Float = 0f,
    /** The hand on it, or on its way back from one; null while it lies where the pile puts it. */
    val hand: CardHand? = null,
    /** How far it is into the pile in the first deal: 0 still out of sight below, 1 in place. */
    val dealt: Float = 1f,
    /** Below 1 for a card rising out of the pile as it comes into view. */
    val alpha: Float = 1f,
)

/** What a hand does to a card: where it pulled it, how that turns it, how hard it presses. */
internal data class CardHand(
    /** In px. */
    val offset: Offset = Offset.Zero,
    /** In degrees. */
    val rotation: Float = 0f,
    val press: Float = 1f,
)

/**
 * One sheet of the pile, drawn where [pose] says. [depth] is its place in the pile as a whole
 * number, which is what the fan of the first deal opens by. [drag] is what the card is told
 * about the hand on it, see [EmailStackState.dragOf]. [scroll] is how far the mail on it is
 * scrolled; only a lifted card is, see [es.jvbabi.overmail.ui.lift.LiftState], the others lie at
 * its top.
 */
@Composable
internal fun StackCard(
    email: Email,
    body: StackCardBody,
    depth: Int,
    pose: () -> CardPose,
    drag: CardDrag?,
    modifier: Modifier = Modifier,
    scroll: ScrollOffset = remember(body) { ScrollOffset() },
) {
    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .cardPose(pose, depth, email.id.toString())
            .cardSurface(place = { pose().place }),
    ) {
        // The whole card, starting below the header: scrolled, it goes on behind it.
        CardBody(
            body = body,
            scroll = scroll,
            topInset = headerHeight,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState),
        )
        CardHeader(
            email = email,
            hazeState = hazeState,
            modifier = Modifier.onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
        )

        if (drag?.towards != null) SwipeOverlay(towards = drag.towards, progress = drag.progress, isEnough = drag.action != null)
    }
}

/** Puts the card where [pose] says, read while drawing. [id] seeds the card's own skew. */
private fun Modifier.cardPose(pose: () -> CardPose, depth: Int, id: String) = graphicsLayer {
    val pose = pose()
    // Out of line only below the top: the card you are reading lies straight. A card on its way
    // up straightens out as it goes.
    val loose = pose.place.coerceIn(0f, 1f)
    // Turning around the bottom edge makes the first deal read as one hand opening, and scaling
    // around it keeps the cards below showing at the bottom.
    transformOrigin = TransformOrigin(0.5f, 1f)
    translationX = jitter(id, 2, 10f) * loose * density
    translationY = pose.place * DEPTH_OFFSET.toPx() + jitter(id, 3, 6f) * loose * density
    rotationZ = jitter(id, 1, 2.5f) * loose
    scaleX = 1f - pose.place * DEPTH_SCALE + jitter(id, 4, 0.01f) * loose
    scaleY = scaleX

    pose.hand?.let { hand ->
        translationX += hand.offset.x
        translationY += hand.offset.y
        // Held, the card turns around its middle, not around its bottom edge.
        transformOrigin = TransformOrigin.Center
        rotationZ += hand.rotation
        scaleX *= hand.press
        scaleY = scaleX
    }

    alpha = pose.alpha

    val out = 1f - pose.dealt
    if (out != 0f) {
        // Past the full height of the pile, so every sheet starts out of sight.
        translationY += out * (size.height * 1.3f + depth * DEPTH_OFFSET.toPx() + jitter(id, 6, 5f) * density)
        // Every other sheet to the other side, so the fan opens around the middle, and wider
        // towards the back.
        rotationZ += out * ((if (depth % 2 == 0) -1 else 1) * (7f + depth * 4f) + jitter(id, 7, 2f))
    }
}

/**
 * The sheet itself: shadow, shape and paper, and the tint that sets a card further down the pile
 * back. Opaque paper under a tint of the background rather than a transparent card: the cards
 * below would show through the ones above them.
 */
@Composable
private fun Modifier.cardSurface(place: () -> Float): Modifier {
    val tint = MaterialTheme.colorScheme.background
    return this
        .dropShadow(CARD_SHAPE, shadow = Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.12f), offset = DpOffset(0.dp, 8.dp)))
        .clip(CARD_SHAPE)
        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .drawWithContent {
            drawContent()
            val place = place()
            if (place > 0f) drawRect(tint, alpha = (0.05f + place * 0.15f).coerceAtMost(0.65f))
        }
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
