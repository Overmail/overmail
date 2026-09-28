package es.jvbabi.overmail.ui.transition

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlin.uuid.Uuid

/**
 * How long a mail takes to grow out of its row into its page, and back. What the NavDisplay's
 * transitions for the page last: the page reads how far it is from theirs, see [rememberScreenProgress].
 */
const val MAIL_TRANSITION_MILLIS = 450

/** How round the corners of the page are while it is on the way, as round as a row's at the start. */
private val ROW_CORNER = 16.dp

/** How dark what lies behind the page gets once it is all the way open. */
private const val SCRIM_ALPHA = 0.16f

/**
 * How far the screen this is composed in has come: 0 before it comes in and once it has gone, 1
 * while it is all there. Follows the NavDisplay's transition, so a predictive back moves it with
 * the finger. 1 outside of a NavDisplay.
 */
val LocalScreenProgress = staticCompositionLocalOf<() -> Float> { { 1f } }

/** Whether the screen this is composed in is all there, not on its way in or out. */
@Composable
fun isScreenSettled(): Boolean = LocalScreenProgress.current() >= 1f

/**
 * [LocalScreenProgress] for the NavEntry this is composed in, read from how far its transition has
 * played. The transitions of a mail's page are an alpha that does not change and only lasts
 * [MAIL_TRANSITION_MILLIS], so that is what the progress runs over; how it looks is drawn from it.
 */
@Composable
fun rememberScreenProgress(): () -> Float {
    val transition = LocalNavAnimatedContentScope.current.transition
    return remember(transition) {
        {
            val total = transition.totalDurationNanos
            val played = if (total <= 0L) 1f else (transition.playTimeNanos.toFloat() / total).coerceIn(0f, 1f)
            when {
                transition.currentState == transition.targetState ->
                    if (transition.currentState == EnterExitState.Visible) 1f else 0f
                transition.targetState == EnterExitState.Visible -> played
                else -> 1f - played
            }
        }
    }
}

/**
 * A part of a mail that both a row and its page show, carried from the one place to the other on
 * the way and crossfaded from how the one shows it into how the other does. [scale] is how the
 * picture of one end is fitted into where the part is on the way.
 */
enum class MailPart(internal val scale: PartScale, internal val alignEnd: Boolean = false) {
    Avatar(PartScale.Width),

    /** One line in a row, a heading on the page: another font, and larger. */
    Subject(PartScale.Width),

    /** One line at both ends. */
    Sender(PartScale.Height),

    /** Short in a row and in full on the page, right-aligned at both. */
    SentAt(PartScale.Height, alignEnd = true),

    /** As many as fit in a line in a row, all of them on the page: moved, not scaled. */
    Labels(PartScale.None),
}

internal enum class PartScale { Width, Height, None }

/** What one end draws of the mail, and where; the picture is kept up to date as it draws. */
internal class Snapshot(val emailId: Uuid, val layer: GraphicsLayer) {
    var bounds by mutableStateOf(Rect.Zero)
}

/** The row and the page of a mail, or one of their parts. */
internal data class SnapshotKey(val part: MailPart?)

/**
 * A mail growing out of the row that was tapped into its page, and back: the page's background
 * grows out of the row's bounds and clip, what the row shows fades out in it, the page fades in,
 * and the [MailPart]s both show move between their two places. Drawn by the page, see [mailPage],
 * from pictures the row and the page take of themselves, see [mailRow] and [mailPart]; how far it
 * is comes from the page's NavDisplay transition, so the back gesture drives it.
 *
 * One for the whole app, [LocalMailTransition]: one page is on its way at a time.
 */
@Stable
class MailTransition {
    /** How far the page of [pageEmailId] has come, while there is one; see [LocalScreenProgress]. */
    private var progress: (() -> Float)? by mutableStateOf(null)
    private var pageEmailId: Uuid? by mutableStateOf(null)

    private val rows = mutableStateMapOf<SnapshotKey, Snapshot>()
    private val pages = mutableStateMapOf<SnapshotKey, Snapshot>()

    /** Whether the mail of [emailId] is on its way into or out of its page; read while drawing. */
    fun isOnTheWay(emailId: Uuid): Boolean {
        val progress = progress ?: return false
        return pageEmailId == emailId && progress() < 1f
    }

    internal fun attachPage(emailId: Uuid, progress: () -> Float) {
        this.pageEmailId = emailId
        this.progress = progress
    }

    internal fun detachPage(progress: () -> Float) {
        if (this.progress !== progress) return
        this.progress = null
        this.pageEmailId = null
    }

    internal fun register(isRow: Boolean, key: SnapshotKey, snapshot: Snapshot) {
        (if (isRow) rows else pages)[key] = snapshot
    }

    internal fun unregister(isRow: Boolean, key: SnapshotKey, snapshot: Snapshot) {
        val map = if (isRow) rows else pages
        if (map[key] === snapshot) map.remove(key)
    }

    /**
     * Everything of the transition, the page's content included, drawn by the page at [progress];
     * [pageBounds] is where the page is, in the root, [drawContent] draws what it shows.
     */
    internal fun DrawScope.draw(
        emailId: Uuid,
        progress: Float,
        pageBounds: Rect,
        pageLayer: GraphicsLayer,
        background: Color,
        cardColor: Color,
        corner: Dp,
    ) {
        val row = rows[SnapshotKey(null)]?.takeIf { it.emailId == emailId }
        val eased = FastOutSlowInEasing.transform(progress)
        val origin = row?.bounds?.takeIf { it != Rect.Zero }

        // Opened from somewhere that does not show the mail: the page only fades in.
        if (origin == null) {
            pageLayer.alpha = progress
            drawRect(background, alpha = progress)
            drawLayer(pageLayer)
            return
        }

        val card = lerp(origin, pageBounds, eased)
        val local = card.translate(-pageBounds.topLeft)
        val radius = lerp(ROW_CORNER.toPx(), corner.toPx(), eased)
        val clip = Path().apply { addRoundRect(RoundRect(local, CornerRadius(radius))) }

        drawRect(Color.Black, alpha = SCRIM_ALPHA * eased)
        clipPath(clip) {
            drawRect(lerp(cardColor, background, eased))
            // The row, going along with the top of the card as it grows.
            translate(local.left, local.top) {
                row.layer.alpha = 1f - fraction(progress, 0f, 0.35f)
                drawLayer(row.layer)
            }
            // The page, laid out as it is when it is there, its top at the card's.
            translate(local.left, local.top) {
                pageLayer.alpha = fraction(progress, 0.25f, 0.8f)
                drawLayer(pageLayer)
            }
            MailPart.entries.forEach { part -> drawPart(emailId, part, progress, eased, pageBounds, local.topLeft) }
        }
    }

    private fun DrawScope.drawPart(emailId: Uuid, part: MailPart, progress: Float, eased: Float, pageBounds: Rect, cardOffset: Offset) {
        val key = SnapshotKey(part)
        val from = rows[key]?.takeIf { it.emailId == emailId }
        val to = pages[key]?.takeIf { it.emailId == emailId }
        val crossfade = fraction(progress, 0.15f, 0.75f)
        when {
            from != null && to != null -> {
                val bounds = lerp(from.bounds, to.bounds, eased).translate(-pageBounds.topLeft)
                drawSnapshot(from, part, bounds, 1f - crossfade)
                drawSnapshot(to, part, bounds, crossfade)
            }
            // Only the page has it: where it is on the page, with the page.
            to != null -> drawSnapshot(to, part, to.bounds.translate(-pageBounds.topLeft + cardOffset), fraction(progress, 0.25f, 0.8f))
            // Only the row has it: where it is in the row, with the row.
            from != null -> {
                val row = rows[SnapshotKey(null)] ?: return
                val inRow = from.bounds.translate(-row.bounds.topLeft + cardOffset)
                drawSnapshot(from, part, inRow, 1f - fraction(progress, 0f, 0.35f))
            }
        }
    }

    private fun DrawScope.drawSnapshot(snapshot: Snapshot, part: MailPart, bounds: Rect, alpha: Float) {
        if (alpha <= 0f) return
        val size = snapshot.layer.size
        if (size.width <= 0 || size.height <= 0) return
        val factor = when (part.scale) {
            PartScale.Width -> bounds.width / size.width
            PartScale.Height -> bounds.height / size.height
            PartScale.None -> 1f
        }
        val left = if (part.alignEnd) bounds.right - size.width * factor else bounds.left
        translate(left, bounds.top) {
            scale(factor, pivot = Offset.Zero) {
                snapshot.layer.alpha = alpha
                drawLayer(snapshot.layer)
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
fun Modifier.mailRow(emailId: Uuid): Modifier = snapshot(emailId, part = null, isRow = true)

/** [part] of the mail of [emailId] in its row, or on its page when not [isRow]; see [MailTransition]. */
@Composable
fun Modifier.mailPart(emailId: Uuid, part: MailPart, isRow: Boolean): Modifier = snapshot(emailId, part, isRow)

@Composable
private fun Modifier.snapshot(emailId: Uuid, part: MailPart?, isRow: Boolean): Modifier {
    val transition = LocalMailTransition.current ?: return this
    val layer = rememberGraphicsLayer()
    val snapshot = remember(emailId, layer) { Snapshot(emailId, layer) }
    val key = SnapshotKey(part)
    DisposableEffect(transition, snapshot, isRow, key) {
        transition.register(isRow, key, snapshot)
        onDispose { transition.unregister(isRow, key, snapshot) }
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
    val layer = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .drawWithContent {
            val now = progress()
            if (transition == null || now >= 1f) {
                drawRect(background)
                drawContent()
                return@drawWithContent
            }
            layer.record { this@drawWithContent.drawContent() }
            with(transition) { draw(emailId, now, bounds, layer, background, cardColor, corner) }
        }
}
