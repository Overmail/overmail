package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.page.home.PREVIEW_ITEMS
import es.jvbabi.overmail.ui.components.ScrollOffset
import es.jvbabi.overmail.ui.lift.liftable
import es.jvbabi.overmail.ui.theme.AppTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_stack_empty
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

val CARD_PADDING = 16.dp

/** The card on top and the ones below it that still show; deeper ones are not drawn at all. */
private const val VISIBLE_CARDS = 4

/** How long a sheet of the first deal takes to come up into the pile. */
private const val DEAL_DURATION = 750

/** Between two sheets of the first deal, the bottom one first. */
private const val DEAL_STAGGER = 70L

/** Fast off the bottom edge, a hair past the resting spot, settled -- the web app's lay-down. */
private val DEAL_EASING = CubicBezierEasing(0.2f, 1.04f, 0.32f, 1f)

/**
 * The pile of [emails], the first one on top, each saying what [bodies] has of it. Only draws; the
 * swipe comes in through [state], see [emailStackSwipe].
 */
@Composable
fun EmailStack(
    emails: List<Email>,
    bodies: Map<Uuid, StackCardBody>,
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
                PiledCard(
                    email = email,
                    body = bodies[email.id] ?: StackCardBody.Loading,
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
 * A [StackCard] in the pile: where [state] and the card's own way into the pile put it.
 * [dealDelay] is set for a card of the first deal, which comes in from below after that long --
 * the bottom of the pile is put down first and the top one lands last.
 */
@Composable
private fun PiledCard(
    email: Email,
    body: StackCardBody,
    depth: Int,
    state: EmailStackState,
    dealDelay: Long?,
) {
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
        delay(dealDelay.milliseconds)
        dealt.animateTo(1f, tween(DEAL_DURATION, easing = DEAL_EASING))
        // Every sheet is felt landing, one after the other.
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    val scroll = remember(body) { ScrollOffset() }

    StackCard(
        email = email,
        body = body,
        depth = depth,
        pose = {
            val motion = state.motionOf(email.id)
            CardPose(
                // A card moves up the pile as the one above it is pulled away.
                place = (if (depth == 0 || motion?.leaving == true) 0f else depth - state.progress) + (1f - appeared.value),
                hand = motion?.let { CardHand(offset = it.offset, rotation = state.rotationOf(it), press = it.press.value) },
                dealt = dealt.value,
                alpha = appeared.value,
            )
        },
        drag = state.dragOf(email.id),
        scroll = scroll,
        // Held still, the card is lifted off the pile as it is, see EmailStackState.press.
        modifier = Modifier.liftable(state.lift, email.id, scroll),
    )
}

@Composable
@Preview
private fun EmailStackPreview() {
    AppTheme(dynamicColor = false) {
        val state = rememberEmailStackState()
        EmailStack(
            emails = PREVIEW_ITEMS.map { it.email },
            bodies = emptyMap(),
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
