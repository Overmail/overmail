package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.jvbabi.overmail.domain.model.Label
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.labelContainerColor

/** Past this the row is more badge than mail; the rest is a count. */
private const val MAX_BADGES = 3

private val BadgeShape = RoundedCornerShape(2.dp)
private val BadgeGap = 4.dp

/**
 * The labels of a mail the way the web app's `MailLabelBadges` shows them beside a mail: at most
 * three tinted badges, only as many as fit next to an outlined `+n` for the rest, so the count
 * always has its room.
 */
@Composable
fun LabelBadges(
    labels: List<Label>,
    small: Boolean,
    modifier: Modifier = Modifier,
) {
    val candidates = labels.take(MAX_BADGES)

    SubcomposeLayout(modifier) { constraints ->
        val gap = BadgeGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)

        val badges = subcompose("badges") {
            candidates.forEach { LabelBadge(name = it.name, color = it.color, small = small) }
        }.map { it.measure(loose) }
        // The widest the count can get, whatever it ends up saying.
        val moreWidth = subcompose("more-probe") {
            LabelBadge(name = "+${labels.size}", color = null, small = small)
        }.single().measure(loose).width

        var used = 0
        var shownCount = badges.size
        for ((index, badge) in badges.withIndex()) {
            used += (if (index > 0) gap else 0) + badge.width
            val needsCount = index + 1 < labels.size
            if (used + (if (needsCount) gap + moreWidth else 0) > constraints.maxWidth) {
                shownCount = index
                break
            }
        }

        val hidden = labels.size - shownCount
        val more = if (hidden > 0) subcompose("more") {
            LabelBadge(name = "+$hidden", color = null, small = small)
        }.single().measure(loose) else null

        val placed = badges.take(shownCount) + listOfNotNull(more)
        val width = (placed.sumOf { it.width } + gap * (placed.size - 1).coerceAtLeast(0))
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placed.maxOfOrNull { it.height } ?: 0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, height) {
            var x = 0
            placed.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
        }
    }
}

/**
 * The web app's badge (`ui/badge`, size `sm`): the name on a fill of the label's hue. The color
 * only reaches the fill, at a lightness the theme is known to work against, so the name stays
 * readable whatever color comes in. Without a color the badge is outlined instead.
 */
@Composable
fun LabelBadge(
    modifier: Modifier = Modifier,
    name: String,
    color: Color?,
    small: Boolean,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    fontStyle: TextStyle? = null,
) {
    val font = fontStyle ?: MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val fill = if (color != null) Modifier.background(color.labelContainerColor(), BadgeShape)
    else Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), BadgeShape)

    Row(
        modifier = modifier
            .clip(BadgeShape)
            .then(fill)
            .let { modifier ->
                if (small) modifier.padding(horizontal = 4.dp)
                else modifier.padding(4.dp)
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) leadingIcon()
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = font,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailingIcon != null) trailingIcon()
    }
}

@Composable
@Preview
private fun LabelBadgePreview() {
    AppTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabelBadge(name = "Uni", color = Color(0xFF3B82F6), small = true)
                    LabelBadge(name = "Rechnungen", color = Color(0xFFEF4444), small = true)
                    LabelBadge(name = "HPI", color = Color(0xFF22C55E), small = false)
                    LabelBadge(name = "+2", color = null, small = true)
                }
            }
        }
    }
}
