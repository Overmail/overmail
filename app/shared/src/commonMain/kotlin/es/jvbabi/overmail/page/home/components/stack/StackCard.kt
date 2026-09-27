package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.phosphor.icons.PhIcons
import com.phosphor.icons.bold.ArchiveBold
import com.phosphor.icons.bold.CheckBold
import com.phosphor.icons.bold.SkipForwardBold
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.page.home.PREVIEW_ITEMS
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.ui.theme.LocalBaseColors
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_stack_no_subject

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
 * about the hand on it, see [EmailStackState.dragOf].
 */
@Composable
internal fun StackCard(
    email: Email,
    depth: Int,
    pose: () -> CardPose,
    drag: CardDrag?,
    modifier: Modifier = Modifier,
) {
    // Opaque card under a tint of the background rather than a transparent card: the cards
    // below would show through the ones above them.
    val tint = MaterialTheme.colorScheme.background
    val id = email.id.toString()

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val pose = pose()
                // Out of line only below the top: the card you are reading lies straight. A card
                // on its way up straightens out as it goes.
                val loose = pose.place.coerceIn(0f, 1f)
                // Turning around the bottom edge makes the first deal read as one hand opening,
                // and scaling around it keeps the cards below showing at the bottom.
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
                val place = pose().place
                if (place > 0f) drawRect(tint, alpha = (0.05f + place * 0.15f).coerceAtMost(0.65f))
            },
    ) {
        Text(
            text = email.subject ?: stringResource(Res.string.home_stack_no_subject),
            style = MaterialTheme.typography.headlineSmall,
            color = if (email.subject == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )

        if (drag?.towards != null) {
            val colorSet = when (drag.towards) {
                StackSwipe.Archive -> LocalBaseColors.current.emerald
                StackSwipe.Keep -> LocalBaseColors.current.blue
            }

            CompositionLocalProvider(LocalContentColor provides colorSet.onContainer) {
                Box(
                    modifier = Modifier
                        .alpha(drag.progress)
                        .fillMaxSize()
                        .background(colorSet.container)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(32.dp)
                            .fillMaxSize(),
                        horizontalAlignment = when (drag.towards) {
                            StackSwipe.Archive -> Alignment.End
                            StackSwipe.Keep -> Alignment.Start
                        }
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.fillMaxSize(),
                                progress = { drag.progress },
                                color = LocalContentColor.current,
                                trackColor = Color.Transparent,
                            )
                            AnimatedContent(
                                targetState = drag.action != null,
                                modifier = Modifier.size(48.dp),
                                transitionSpec = { scaleIn() togetherWith scaleOut() }
                            ) { isDraggedEnough ->
                                if (isDraggedEnough) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                            .background(colorSet.onContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = PhIcons.Bold.CheckBold,
                                            contentDescription = null,
                                            tint = colorSet.container,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                } else Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = when (drag.towards) {
                                            StackSwipe.Archive -> PhIcons.Bold.ArchiveBold
                                            StackSwipe.Keep -> PhIcons.Bold.SkipForwardBold
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = LocalContentColor.current
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.headlineSmall) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(.75f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp, when (drag.towards) {
                                    StackSwipe.Archive -> Alignment.End
                                    StackSwipe.Keep -> Alignment.Start
                                })
                            ) {
                                AnimatedContent(
                                    targetState = drag.action != null,
                                ) { isDraggedEnough ->
                                    if (isDraggedEnough) Text("Loslassen")
                                    else Text("Ziehen")
                                }
                                Text("zum")
                                Text(
                                    text = when (drag.towards) {
                                        StackSwipe.Archive -> "Archivieren"
                                        StackSwipe.Keep -> "Überspringen"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
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

/** A phone-sized box with room around the card, so a card pulled aside or turned still shows. */
@Composable
private fun CardPreviewFrame(content: @Composable () -> Unit) {
    AppTheme(dynamicColor = false) {
        Box(
            modifier = Modifier
                .size(width = 360.dp, height = 520.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 32.dp, vertical = 48.dp),
        ) {
            content()
        }
    }
}

/** One card on its own, [hand] and [drag] as a swipe would set them. */
@Composable
private fun SingleCardPreview(
    email: Email = PREVIEW_ITEMS.first().email,
    hand: CardHand? = null,
    drag: CardDrag? = null,
) {
    CardPreviewFrame {
        StackCard(email = email, depth = 0, pose = { CardPose(hand = hand) }, drag = drag)
    }
}

@Composable
@Preview
private fun StackCardRestingPreview() {
    SingleCardPreview()
}

@Composable
@Preview
private fun StackCardNoSubjectPreview() {
    SingleCardPreview(email = PREVIEW_ITEMS.first().email.copy(subject = null))
}

@Composable
@Preview
private fun StackCardPressedPreview() {
    SingleCardPreview(
        hand = CardHand(press = 0.96f),
        drag = CardDrag(isHeld = true, towards = null, progress = 0f, action = null),
    )
}

@Composable
@Preview
private fun StackCardPulledTowardsArchivePreview() {
    SingleCardPreview(
        hand = CardHand(offset = Offset(-120f, 20f), rotation = -5f, press = 0.96f),
        drag = CardDrag(isHeld = true, towards = StackSwipe.Archive, progress = 0.5f, action = null),
    )
}

@Composable
@Preview
private fun StackCardPastArchivePreview() {
    SingleCardPreview(
        hand = CardHand(offset = Offset(-300f, 40f), rotation = -12f, press = 0.96f),
        drag = CardDrag(isHeld = true, towards = StackSwipe.Archive, progress = 1.3f, action = StackSwipe.Archive),
    )
}

@Composable
@Preview
private fun StackCardPastKeepPreview() {
    SingleCardPreview(
        hand = CardHand(offset = Offset(300f, -30f), rotation = 12f, press = 0.96f),
        drag = CardDrag(isHeld = true, towards = StackSwipe.Keep, progress = 1.3f, action = StackSwipe.Keep),
    )
}

@Composable
@Preview
private fun StackCardSpringingBackPreview() {
    SingleCardPreview(
        hand = CardHand(offset = Offset(60f, 10f), rotation = 2f, press = 0.98f),
        drag = CardDrag(isHeld = false, towards = StackSwipe.Keep, progress = 0.25f, action = null),
    )
}

@Composable
@Preview
private fun StackCardThrownPreview() {
    SingleCardPreview(
        hand = CardHand(offset = Offset(520f, 60f), rotation = 18f),
        drag = CardDrag(isHeld = false, towards = StackSwipe.Keep, progress = 2.2f, action = StackSwipe.Keep),
    )
}

/**
 * A pile of four, the top one pulled [pull] of the way to the threshold: the cards below move up
 * a place as it goes.
 */
@Composable
private fun PilePreview(pull: Float = 0f, dealt: Float = 1f) {
    val emails = PREVIEW_ITEMS.take(4).map { it.email }
    CardPreviewFrame {
        for (depth in emails.indices.reversed()) {
            val hand = if (depth == 0 && pull > 0f) CardHand(offset = Offset(-240f * pull, 0f), rotation = -8f * pull, press = 0.96f) else null
            StackCard(
                email = emails[depth],
                depth = depth,
                pose = { CardPose(place = if (depth == 0) 0f else depth - pull, hand = hand, dealt = dealt) },
                drag = null,
            )
        }
    }
}

@Composable
@Preview
private fun StackCardPilePreview() {
    PilePreview()
}

@Composable
@Preview
private fun StackCardPileWhilePulledPreview() {
    PilePreview(pull = 0.6f)
}

@Composable
@Preview
private fun StackCardPileBeingDealtPreview() {
    PilePreview(dealt = 0.7f)
}

@Composable
@Preview
private fun StackCardRisingIntoViewPreview() {
    CardPreviewFrame {
        StackCard(email = PREVIEW_ITEMS[1].email, depth = 3, pose = { CardPose(place = 3.5f, alpha = 0.5f) }, drag = null)
        StackCard(email = PREVIEW_ITEMS[0].email, depth = 0, pose = { CardPose() }, drag = null)
    }
}
