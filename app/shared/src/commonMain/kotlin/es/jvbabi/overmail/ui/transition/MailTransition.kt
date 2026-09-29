package es.jvbabi.overmail.ui.transition

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.shadow.ShadowContext
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlinx.coroutines.flow.collectLatest
import kotlin.uuid.Uuid

/**
 * How long a mail takes to grow out of its row into its page, and back, see [MailScene], which
 * runs the page's progress over it.
 */
const val MAIL_TRANSITION_MILLIS = 250

/** How round the corners of the card are while it grows, as round as a row's at the start. */
private val ROW_CORNER = 16.dp

/** How dark what lies behind the page gets once it is all the way open. */
private const val SCRIM_ALPHA = 0.16f

/** How far the row slides up as it fades out, and the page slides up into place as it fades in. */
private val CONTENT_SLIDE = 24.dp

/** How long the page takes to lose its corners and shadow once it has settled, and to get them back. */
private const val CARD_SETTLE_MILLIS = 180

/** The shadow of the card while it is lifted off the screen below, at its strongest. */
private val CARD_SHADOW = Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.18f), offset = DpOffset(0.dp, 8.dp))

/**
 * How far the screen this is composed in has come: 0 before it comes in and once it has gone, 1
 * while it is all there. Follows the NavDisplay's transition, or a mail's page's own, see
 * [MailScene]; a predictive back moves it with the finger either way. 1 outside of a NavDisplay.
 */
val LocalScreenProgress = staticCompositionLocalOf<() -> Float> { { 1f } }

/** Whether the screen this is composed in is all there, not on its way in or out. */
@Composable
fun isScreenSettled(): Boolean = LocalScreenProgress.current() >= 1f

/** [LocalScreenProgress] for the NavEntry this is composed in, read from how far its transition has played. */
@Composable
fun rememberScreenProgress(): () -> Float {
    val transition = LocalNavAnimatedContentScope.current.transition
    return remember(transition) {
        {
            val total = transition.totalDurationNanos
            val played = { (transition.playTimeNanos.toFloat() / total).coerceIn(0f, 1f) }
            when {
                transition.currentState == transition.targetState ->
                    if (transition.currentState == EnterExitState.Visible) 1f else 0f
                // Nothing to play: on the way in that is its first frame, before its animations
                // are in; otherwise a back gesture that was let go of, which leaves it headed for
                // where it already is.
                total <= 0L -> when {
                    transition.targetState != EnterExitState.Visible -> 1f
                    transition.currentState == EnterExitState.PreEnter -> 0f
                    else -> 1f
                }
                transition.targetState == EnterExitState.Visible -> played()
                else -> 1f - played()
            }
        }
    }
}


/** What the row draws of the mail, and where; the picture is kept up to date as it draws. */
internal class RowSnapshot(val emailId: Uuid, val layer: GraphicsLayer) {
    var bounds by mutableStateOf(Rect.Zero)
}

/**
 * A mail growing out of the row that was tapped into its page, and back: the page's background
 * grows out of the row's bounds as a card with round corners and a shadow, what the row shows
 * fades out sliding up, and the page fades in sliding up into place. Drawn by the page, see
 * [mailPage], from a picture the row takes of itself, see [mailRow]; how far it is comes from the
 * page's [MailScene], so the back gesture drives it.
 *
 * One for the whole app, [LocalMailTransition]: one page is on its way at a time.
 */
@Stable
class MailTransition {
    /** How far the page of [pageEmailId] has come, while there is one; see [LocalScreenProgress]. */
    private var progress: (() -> Float)? by mutableStateOf(null)
    private var pageEmailId: Uuid? by mutableStateOf(null)

    private var row: RowSnapshot? by mutableStateOf(null)

    /** Whether the mail of [emailId] is on its way into or out of its page; read while drawing. */
    fun isOnTheWay(emailId: Uuid): Boolean {
        val progress = progress ?: return false
        // Not at 0 either: a page whose way out has ended is no longer drawn, but it is attached
        // until it is disposed a frame later, and the row would be missing in that frame.
        return pageEmailId == emailId && progress().let { it > 0f && it < 1f }
    }

    /** Whether a mail's page is there, or on its way in or out. */
    fun hasPage(): Boolean = pageEmailId != null

    internal fun attachPage(emailId: Uuid, progress: () -> Float) {
        this.pageEmailId = emailId
        this.progress = progress
    }

    internal fun detachPage(progress: () -> Float) {
        if (this.progress !== progress) return
        this.progress = null
        this.pageEmailId = null
    }

    internal fun register(snapshot: RowSnapshot) {
        row = snapshot
    }

    internal fun unregister(snapshot: RowSnapshot) {
        if (row === snapshot) row = null
    }

    /**
     * Everything of the transition, the page's content included, drawn by the page at [progress];
     * [pageBounds] is where the page is, in the root, [pageLayer] what it shows. [card] is how much
     * of a card it is, round corners and shadow: all of one on its way, none once it has settled.
     */
    internal fun DrawScope.draw(
        emailId: Uuid,
        progress: Float,
        pageBounds: Rect,
        pageLayer: GraphicsLayer,
        background: Color,
        cardColor: Color,
        corner: Dp,
        card: Float,
        shadows: ShadowContext,
    ) {
        val row = row?.takeIf { it.emailId == emailId }
        val eased = FastOutSlowInEasing.transform(progress)
        val origin = row?.bounds?.takeIf { it != Rect.Zero }
        val slide = CONTENT_SLIDE.toPx()
        // The page coming in: faded in and slid up into place, over the second half of the way.
        val pageIn = fraction(progress, 0.3f, 0.9f)

        // Opened from somewhere that does not show the mail: the page only fades in, sliding up.
        if (origin == null) {
            drawRect(background, alpha = progress)
            translate(top = (1f - pageIn) * slide) {
                pageLayer.alpha = pageIn
                drawLayer(pageLayer)
            }
            return
        }

        val bounds = lerp(origin, pageBounds, eased)
        val local = bounds.translate(-pageBounds.topLeft)
        val radius = lerp(corner.toPx(), ROW_CORNER.toPx(), card)

        drawRect(Color.Black, alpha = SCRIM_ALPHA * eased)
        // Lifted off the screen below for as long as it is a card.
        if (card > 0f) translate(local.left, local.top) {
            val shadow = shadows.createDropShadowPainter(RoundedCornerShape(radius), CARD_SHADOW)
            with(shadow) { draw(local.size, alpha = card) }
        }

        val clip = Path().apply { addRoundRect(RoundRect(local, CornerRadius(radius))) }
        clipPath(clip) {
            drawRect(lerp(cardColor, background, eased))
            // The row, going along with the top of the card as it grows, fading out sliding up.
            val rowOut = fraction(progress, 0f, 0.35f)
            translate(local.left, local.top - rowOut * slide) {
                row.layer.alpha = 1f - rowOut
                drawLayer(row.layer)
            }
            // The page, laid out as it is when it is there, its top at the card's.
            translate(local.left, local.top + (1f - pageIn) * slide) {
                pageLayer.alpha = pageIn
                drawLayer(pageLayer)
            }
        }
    }
}

/** How far [value] is from [start] to [end], clamped to 0 to 1. */
private fun fraction(value: Float, start: Float, end: Float) = ((value - start) / (end - start)).coerceIn(0f, 1f)

/** The one [MailTransition] of the app; null outside of it, in a preview, where nothing moves. */
val LocalMailTransition = staticCompositionLocalOf<MailTransition?> { null }

/**
 * The row of the mail of [emailId] in a listing, which its page grows out of and shrinks back
 * into. Not drawn while the mail is on its way: the page draws it then, in the card that grows.
 * Only for the one row that is to grow: every row taking pictures of itself costs.
 */
@Composable
fun Modifier.mailRow(emailId: Uuid): Modifier {
    val transition = LocalMailTransition.current ?: return this
    val layer = rememberGraphicsLayer()
    val snapshot = remember(emailId, layer) { RowSnapshot(emailId, layer) }
    DisposableEffect(transition, snapshot) {
        transition.register(snapshot)
        onDispose { transition.unregister(snapshot) }
    }
    return this
        .onGloballyPositioned { snapshot.bounds = it.boundsInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            if (transition.isOnTheWay(emailId)) return@drawWithContent
            // The page may have faded it on the way.
            layer.alpha = 1f
            drawLayer(layer)
        }
}

/**
 * The page of the mail of [emailId], which grows out of the mail's row, see [MailTransition], and
 * is [background] once it is there. It draws its background itself, so what it is put on does not.
 * [cardColor] is what the card is at the start, while it is the row.
 */
@Composable
fun Modifier.mailPage(emailId: Uuid, background: Color, cardColor: Color, corner: Dp = 0.dp): Modifier {
    val transition = LocalMailTransition.current
    val progress = LocalScreenProgress.current
    if (transition != null) DisposableEffect(transition, emailId, progress) {
        transition.attachPage(emailId, progress)
        onDispose { transition.detachPage(progress) }
    }
    val shadows = LocalGraphicsContext.current.shadowContext
    // How much of a card the page is, see MailTransition.draw. Not drawn from the progress: the
    // corners and the shadow go once the page has settled, and come back as soon as it leaves --
    // the last frame of the way in is rarely at its very end, and they would vanish in one step.
    val card = remember { Animatable(1f) }
    LaunchedEffect(progress) {
        snapshotFlow { progress() >= 1f }.collectLatest { isSettled ->
            card.animateTo(if (isSettled) 0f else 1f, tween(CARD_SETTLE_MILLIS))
        }
    }
    val layer = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .drawWithContent {
            val now = progress()
            if (transition == null || (now >= 1f && card.value <= 0f)) {
                drawRect(background)
                drawContent()
                return@drawWithContent
            }
            layer.record { this@drawWithContent.drawContent() }
            with(transition) { draw(emailId, now, bounds, layer, background, cardColor, corner, card.value, shadows) }
        }
}
