package es.jvbabi.overmail.ui.transition

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.SharedTransitionScope.SharedContentState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.uuid.Uuid

/**
 * The scope the screens share elements in, laid around the whole NavDisplay in `App`; null
 * outside of it, in a preview, where nothing is shared.
 */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * How the screen this is composed in comes and goes: its entry's scope in the NavDisplay, handed
 * down by `App`. Null outside of one, like [LocalSharedTransitionScope].
 */
val LocalScreenAnimationScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The part of a mail that is carried from wherever it was tapped to its own page. Everything but
 * the container lies above it on the way, and crossfades from how the one end shows it into how
 * the other does -- another font, another size, a longer date.
 */
enum class SharedMailPart(internal val resizeMode: SharedTransitionScope.ResizeMode) {
    /** What was tapped, and the whole page it grows into: laid out anew at every size on the way. */
    Container(SharedTransitionScope.ResizeMode.RemeasureToBounds),

    /** The sender's picture, which stays one picture on the way. */
    Avatar(SharedTransitionScope.ResizeMode.RemeasureToBounds),

    /** Scaled by its width, so a line of it grows into the heading it becomes. */
    Subject(SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopStart)),

    /** Who sent it; one line at both ends, so scaled by its height. */
    Sender(SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillHeight, Alignment.CenterStart)),

    /** When it was sent, short in a row and in full on the page. */
    SentAt(SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillHeight, Alignment.CenterEnd)),

    /** Laid out anew as it grows: a row shows as many as fit in a line, the page all of them. */
    Labels(SharedTransitionScope.ResizeMode.RemeasureToBounds),
}

/**
 * How long a mail takes to grow into its page and back. The screens' own transitions last as
 * long: the bounds are only animated while the transition of the screen they head for runs.
 */
const val SHARED_MAIL_MILLIS = 400

private val SHARED_MAIL_BOUNDS = BoundsTransform { _, _ -> tween(SHARED_MAIL_MILLIS, easing = FastOutSlowInEasing) }

private data class SharedMailKey(val emailId: Uuid, val part: SharedMailPart)

/**
 * Marks this as [part] of the mail of [emailId]: the same on another screen, across a navigation
 * between the two, is where this grows out of or shrinks back into. Without a counterpart it only
 * fades with its screen -- the mail's page opened from somewhere that does not show the mail.
 *
 * [shape] is what it is cut to while it is on the way -- the container it lies in, without one --
 * and [zIndex] how far above the others of the same transition it is drawn.
 */
@Composable
fun Modifier.sharedMail(
    emailId: Uuid,
    part: SharedMailPart,
    shape: Shape? = null,
    zIndex: Float = if (part == SharedMailPart.Container) 0f else 1f,
    enter: EnterTransition = fadeIn(),
    exit: ExitTransition = fadeOut(),
): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val screen = LocalScreenAnimationScope.current ?: return this
    return with(shared) {
        this@sharedMail.sharedBounds(
            sharedContentState = rememberSharedContentState(SharedMailKey(emailId, part)),
            animatedVisibilityScope = screen,
            enter = enter,
            exit = exit,
            boundsTransform = SHARED_MAIL_BOUNDS,
            resizeMode = part.resizeMode,
            clipInOverlayDuringTransition = shape?.let { OverlayClip(it) } ?: object : OverlayClip {
                override fun getClipPath(
                    sharedContentState: SharedContentState,
                    bounds: Rect,
                    layoutDirection: LayoutDirection,
                    density: Density,
                ): Path? {
                    return sharedContentState.parentSharedContentState?.clipPathInOverlay
                }
            },
            zIndexInOverlay = zIndex,
        )
    }
}

/**
 * How the screen of [navScope] comes and goes, on a transition of its own that follows where the
 * NavDisplay's is headed. Shared elements hang off this one rather than the NavDisplay's: that
 * one is seekable, for predictive back, and a bounds animation a shared element adds to it once
 * it is under way never moves -- it sits at where it started and jumps at the end.
 *
 * It runs in time, so a predictive back plays it rather than following the finger. The screens'
 * own transitions last [SHARED_MAIL_MILLIS] as well, so neither is gone before the other is done.
 */
@Composable
fun rememberScreenAnimationScope(navScope: AnimatedVisibilityScope): AnimatedVisibilityScope {
    val nav = navScope.transition
    val state = remember { MutableTransitionState(nav.currentState) }
    state.targetState = nav.targetState
    val transition = rememberTransition(state, label = "screen")
    return remember(transition) {
        object : AnimatedVisibilityScope {
            override val transition: Transition<EnterExitState> = transition
        }
    }
}

/**
 * Whether the screen this is composed in has arrived and is not on its way out: true outside of a
 * NavDisplay. What a platform view waits for -- it is drawn outside the Compose layer and would
 * neither move nor fade with the screen around it.
 */
@Composable
fun isScreenSettled(): Boolean {
    val transition = LocalScreenAnimationScope.current?.transition ?: return true
    return transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
}
