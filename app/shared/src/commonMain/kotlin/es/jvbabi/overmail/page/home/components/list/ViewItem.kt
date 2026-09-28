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
import androidx.compose.runtime.remember
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
import es.jvbabi.overmail.page.home.components.stack.MailCard
import es.jvbabi.overmail.page.home.components.stack.StackCardBody
import es.jvbabi.overmail.ui.components.LabelBadges
import es.jvbabi.overmail.ui.components.ScrollOffset
import es.jvbabi.overmail.ui.lift.LiftState
import es.jvbabi.overmail.ui.lift.liftOnLongPress
import es.jvbabi.overmail.ui.lift.liftable
import es.jvbabi.overmail.ui.components.ParticipantAvatar
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.sentAtLabel
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

/**
 * What a row needs to preview its mail when it is pressed and held: the lift of the screen and
 * what the mails say, and who to ask for one that is not here yet.
 */
class ListMailPreview(
    val lift: LiftState,
    val bodies: Map<Uuid, StackCardBody>,
    val onLoadBody: (Uuid) -> Unit,
)

/** How much of its way up a preview has to be before it covers the row it grows out of. */
private const val PREVIEW_FADE_IN = 0.3f

/**
 * A mail of the listing. Pressed and held, with a [preview], it lifts a card of the mail over
 * everything, the one the pile lifts, grown out of the row.
 */
@Composable
fun ViewItem(
    item: ViewResult.Item,
    preview: ListMailPreview? = null,
) {
    if (preview == null) return ViewItemRow(item)

    val id = item.email.id
    val scroll = remember(id) { ScrollOffset() }
    val isLifted = preview.lift.isLifted(id)
    LaunchedEffect(isLifted, id) { if (isLifted) preview.onLoadBody(id) }

    Box(Modifier.fillMaxWidth().liftable(preview.lift, id, scroll)) {
        // Inside the click, so the release of a long press is not a click as well.
        ViewItemRow(item, gesture = Modifier.liftOnLongPress(preview.lift, id))
        if (isLifted) MailCard(
            email = item.email,
            body = preview.bodies[id] ?: StackCardBody.Loading,
            scroll = scroll,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = (preview.lift.progress / PREVIEW_FADE_IN).coerceIn(0f, 1f) },
        )
    }
}

@Composable
private fun ViewItemRow(
    item: ViewResult.Item,
    gesture: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {}
            .then(gesture)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ParticipantAvatar(
            participant = item.email.sentBy,
            size = 32.dp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
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
                    modifier = Modifier.alignByBaseline()
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
                modifier = Modifier.padding(top = 4.dp),
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
