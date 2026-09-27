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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.repository.ViewResult
import es.jvbabi.overmail.page.home.PREVIEW_ITEMS
import es.jvbabi.overmail.ui.components.LabelBadges
import es.jvbabi.overmail.ui.components.ParticipantAvatar
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.sentAtLabel
import kotlinx.serialization.builtins.serializer

@Composable
fun ViewItem(
    item: ViewResult.Item,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {}
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
                val fontWeight = if (item.email.isRead) FontWeight.Bold else FontWeight.Normal
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .alignByBaseline(),
                ) sender@{
                    Text(
                        text = item.email.sentBy.name ?: item.email.sentBy.email,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = fontWeight,
                    )
                    if (item.email.isRead) Spacer(
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
                val fontWeight = if (item.email.isRead) FontWeight.Bold else FontWeight.Normal
                val color = if (item.email.subject == null) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onSurface
                Text(
                    text = item.email.subject ?: "Kein Betreff",
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = fontWeight,
                    color = color,
                )
            })
            Text(
                text = "Lorem ipsum dolor sit amet",
                maxLines = 1,
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
