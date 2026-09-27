package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.page.home.PREVIEW_ITEMS
import es.jvbabi.overmail.ui.theme.AppTheme

private val PREVIEW_BODY = StackCardBody.Text(
    """
    Hallo zusammen,

    anbei wie besprochen die Zahlen für das zweite Quartal. Die Ausgaben liegen leicht unter dem Plan, vor allem weil die Reisekosten niedriger waren als angenommen.

    Bitte schaut bis Freitag drüber und meldet euch, falls etwas nicht passt. Am Montag stellen wir den Bericht dann im großen Kreis vor.

    Viele Grüße
    Julia
    """.trimIndent()
)

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
    body: StackCardBody = PREVIEW_BODY,
    hand: CardHand? = null,
    drag: CardDrag? = null,
) {
    CardPreviewFrame {
        StackCard(email = email, body = body, depth = 0, pose = { CardPose(hand = hand) }, drag = drag)
    }
}

@Composable
@Preview
private fun StackCardRestingPreview() {
    SingleCardPreview()
}

@Composable
@Preview
private fun StackCardLoadingPreview() {
    SingleCardPreview(body = StackCardBody.Loading)
}

@Composable
@Preview
private fun StackCardFailedPreview() {
    SingleCardPreview(body = StackCardBody.Failed)
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
                body = PREVIEW_BODY,
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
        StackCard(email = PREVIEW_ITEMS[1].email, body = PREVIEW_BODY, depth = 3, pose = { CardPose(place = 3.5f, alpha = 0.5f) }, drag = null)
        StackCard(email = PREVIEW_ITEMS[0].email, body = PREVIEW_BODY, depth = 0, pose = { CardPose() }, drag = null)
    }
}
