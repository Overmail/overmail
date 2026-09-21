package es.jvbabi.overmail.page.home.components.filter.label_search

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.Check
import com.phosphor.icons.regular.Tag
import es.jvbabi.overmail.utils.labelContentColor
import org.jetbrains.compose.resources.pluralStringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_labels_email_count

/**
 * One row of the list: menu-sized rather than a full list item, so a screen holds many.
 *
 * @param leading what the row shows in front, a person's face say; the tag in [color] without it.
 *   Either way the tick flips over it while the row is picked.
 */
@Composable
fun Item(
    color: Color,
    name: String,
    subtitle: String?,
    /** Null for an entry that is not counted in mails, like the account's own addresses. */
    emailCount: Long?,
    picked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The tick takes the tag's place rather than a column of its own, flipping over to it and
        // back. Past the halfway point the other face shows, turned round so it does not read
        // mirrored.
        val rotation by animateFloatAsState(targetValue = if (picked) 180f else 0f)
        Box(
            modifier = Modifier
                .size(if (leading == null) 18.dp else 24.dp)
                .graphicsLayer {
                    rotationY = if (rotation > 90f) rotation - 180f else rotation
                    cameraDistance = 12f * density
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                rotation > 90f -> Icon(
                    imageVector = PhIcons.Regular.Check,
                    contentDescription = null,
                    tint = color.labelContentColor(),
                    modifier = Modifier.size(18.dp),
                )
                leading != null -> leading()
                else -> Icon(
                    imageVector = PhIcons.Regular.Tag,
                    contentDescription = null,
                    tint = color.labelContentColor(),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (emailCount != null) Text(
            text = pluralStringResource(
                Res.plurals.home_labels_email_count,
                emailCount.toInt(),
                emailCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
