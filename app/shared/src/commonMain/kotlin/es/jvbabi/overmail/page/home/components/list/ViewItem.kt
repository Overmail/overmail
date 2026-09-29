package es.jvbabi.overmail.page.home.components.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.repository.ViewResult
import es.jvbabi.overmail.page.home.PREVIEW_ITEMS
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.page.home.components.stack.CARD_SHAPE
import es.jvbabi.overmail.page.home.components.stack.MailCard
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import es.jvbabi.overmail.ui.components.LabelBadges
import es.jvbabi.overmail.ui.components.ScrollOffset
import es.jvbabi.overmail.ui.lift.LiftState
import es.jvbabi.overmail.ui.lift.liftOnLongPress
import es.jvbabi.overmail.ui.lift.liftStandIn
import es.jvbabi.overmail.ui.components.ParticipantAvatar
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.ui.transition.mailRow
import es.jvbabi.overmail.utils.sentAtLabel
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

/**
 * The preview of a mail of the listing, pressed and held: one card for all rows, lifted out of the
 * row that was pressed. The card stays composed from the first press on and only changes mails,
 * so its web view is made once rather than for every preview; see [ListMailPreviewCard].
 */
@Stable
class ListMailPreview(val lift: LiftState) {
    /** The mail last pressed, which the card shows; null until the first press. */
    var email: Email? by mutableStateOf(null)
        private set

    /** Where the row of [email] is, in the root, which the card grows out of. */
    internal var origin: Rect = Rect.Zero

    internal val scroll = ScrollOffset()

    /** A finger went down on the row of [email], at [origin]: the card turns to that mail, in case it is held. */
    internal fun prepare(email: Email, origin: Rect) {
        // One card for every mail: another one is read from its top.
        if (email.id != this.email?.id) scroll.scrollTo(0f)
        this.email = email
        this.origin = origin
    }
}

/** How much of its way up the preview has to be before it covers the row it grows out of. */
private const val PREVIEW_FADE_IN = 0.3f

/**
 * The card of [preview], over everything once a row is pressed and held; nothing until then.
 * [bodies] is what the mails say, [onLoadBody] asks for one that is not there yet.
 */
@Composable
fun ListMailPreviewCard(
    preview: ListMailPreview,
    bodies: Map<Uuid, StackCardBody>,
    onLoadBody: (Uuid) -> Unit,
) {
    val email = preview.email ?: return
    val isLifted = preview.lift.isLifted(preview)
    LaunchedEffect(isLifted, email.id) { if (isLifted) onLoadBody(email.id) }

    MailCard(
        email = email,
        body = bodies[email.id] ?: StackCardBody.Loading,
        scroll = preview.scroll,
        shownHeight = { preview.lift.shownHeight(preview) },
        modifier = Modifier
            .liftStandIn(preview.lift, preview, origin = { preview.origin }, scroll = preview.scroll, shape = CARD_SHAPE)
            .graphicsLayer { alpha = (preview.lift.progress / PREVIEW_FADE_IN).coerceIn(0f, 1f) },
    )
}

/** How a row is cut, and what the mail's page shrinks back into. */
private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * A mail of the listing. Tapped, [onOpen] opens its page, which grows out of the row while it
 * [isShared], see [mailRow]; only the row last opened is, a group composes all of its rows at
 * once. Pressed and held, with a [preview], it lifts a card of the mail over everything,
 * grown out of the row as well.
 */
@Composable
fun ViewItem(
    item: ViewResult.Item,
    preview: ListMailPreview? = null,
    onOpen: (() -> Unit)? = null,
    isShared: Boolean = false,
) {
    if (preview == null) return ViewItemRow(item, onOpen = onOpen, isShared = isShared)

    var bounds by remember { mutableStateOf(Rect.Zero) }
    ViewItemRow(
        item = item,
        onOpen = onOpen,
        isShared = isShared,
        modifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() },
        // Inside the click, so the release of a long press is not a click as well.
        gesture = Modifier.liftOnLongPress(preview.lift, preview, onPress = { preview.prepare(item.email, bounds) }),
    )
}

@Composable
private fun ViewItemRow(
    item: ViewResult.Item,
    modifier: Modifier = Modifier,
    gesture: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    isShared: Boolean = false,
) {
    Row(
        modifier = modifier
            .then(if (isShared) Modifier.mailRow(item.email.id) else Modifier)
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .clickable(enabled = onOpen != null) { onOpen?.invoke() }
            .then(gesture)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ParticipantAvatar(
            participant = item.email.sentBy,
            size = 36.dp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
        )
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val fontWeight = if (!item.email.isRead) FontWeight.Bold else FontWeight.Normal
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .alignByBaseline(),
                ) sender@{
                    Text(
                        text = item.email.sentBy.name ?: item.email.sentBy.email,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = fontWeight,
                        modifier = Modifier
                            .weight(1f, fill = false),
                    )
                    if (!item.email.isRead) Spacer(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(start = 6.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = sentAtLabel(item.email.sentAt),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = fontWeight,
                    modifier = Modifier
                        .alignByBaseline(),
                )
            }
            run(subject@{
                val fontWeight = if (!item.email.isRead) FontWeight.Bold else FontWeight.Normal
                val color = if (item.email.subject == null) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onSurface
                Text(
                    text = item.email.subject ?: "Kein Betreff",
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = fontWeight,
                    color = color,
                )
            })
            if (!item.email.preview.isNullOrEmpty()) Text(
                text = item.email.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            if (item.email.labels.isNotEmpty()) LabelBadges(
                labels = item.email.labels,
                modifier = Modifier
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
@Preview
private fun ViewItemPreview() {
    AppTheme(dynamicColor = false) {
        Surface {
            Column {
                PREVIEW_ITEMS.forEach { ViewItem(item = it) }
            }
        }
    }
}
