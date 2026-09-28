@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.overmail.page.home.components.stack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Easing
import dev.chrisbanes.haze.HazeState
import es.jvbabi.overmail.utils.ProgressiveDirection
import es.jvbabi.overmail.utils.progressiveBackground
import es.jvbabi.overmail.utils.progressiveBackgroundBlur
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.ui.components.EmailPicture
import es.jvbabi.overmail.ui.components.LabelBadge
import es.jvbabi.overmail.ui.components.ScrollOffset
import es.jvbabi.overmail.ui.components.ParticipantAvatar
import es.jvbabi.overmail.utils.sentAtLabel
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.home_stack_body_failed
import overmail.app.shared.generated.resources.home_stack_no_subject

/** What a card knows of what its mail says. */
sealed interface StackCardBody {
    /** On its way from the server, or from the cache. */
    data object Loading : StackCardBody

    /**
     * A mail with an html part, which is what is shown of it then: as its [picture], null until
     * that is made, or while it is not kept -- see `EmailBodies`.
     */
    data class Html(val html: String, val picture: ImageBitmap? = null) : StackCardBody

    /** A mail with only a text part. */
    data class Text(val text: String) : StackCardBody

    data object Failed : StackCardBody
}

/** How far below its content the header's blur runs out, which is where the mail starts. */
private val HEADER_FADE = 24.dp

/** How much the mail behind the header is blurred where the header begins. */
private val HEADER_BLUR = 32.dp

/**
 * Solid for the upper half of the header, then running out: the subject and the sender stay
 * readable over whatever of the mail has been scrolled behind them.
 */
private val HEADER_EASING = Easing { fraction -> EaseInOut.transform(((fraction - 0.5f) / 0.5f).coerceIn(0f, 1f)) }

/**
 * What a card says of its mail before the mail itself: the subject, who sent it, its labels. It
 * lies over the mail, which [hazeState] captures, and blurs and covers it where the mail is
 * scrolled behind it.
 */
@Composable
internal fun CardHeader(email: Email, hazeState: HazeState, modifier: Modifier = Modifier) {
    // The paper of the card, see cardSurface.
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest

    Column(
        modifier = modifier
            .fillMaxWidth()
            .progressiveBackgroundBlur(hazeState = hazeState, direction = ProgressiveDirection.TopToBottom, backgroundColor = paper, startRadius = HEADER_BLUR, easing = HEADER_EASING)
            .progressiveBackground(paper, ProgressiveDirection.TopToBottom, easing = HEADER_EASING)
            .padding(top = 24.dp, bottom = HEADER_FADE)
            .padding(horizontal = 24.dp),
    ) {
        Text(
            text = email.subject ?: stringResource(Res.string.home_stack_no_subject),
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = if (email.subject == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        CardSender(email = email, modifier = Modifier.padding(top = 12.dp))
        if (email.labels.isNotEmpty()) CardLabels(email = email, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Who sent the mail, and when: the name and the address under it, where they differ. */
@Composable
private fun CardSender(email: Email, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        ParticipantAvatar(email.sentBy, size = 32.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            val primary = email.sentBy.name ?: email.sentBy.email
            val secondary = if (email.sentBy.name != null) email.sentBy.email else null

            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f, false)
                        .alignByBaseline(),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )

                Spacer(Modifier.width(4.dp))

                Text(
                    text = sentAtLabel(email.sentAt),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.alignByBaseline(),
                )
            }

            if (secondary != null) Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CardLabels(email: Email, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        email.labels.forEach { label -> LabelBadge(name = label.name, color = label.color) }
    }
}

/** How tall the fade is that a mail longer than its card runs out in. */
private val OVERFLOW_FADE = 48.dp

/**
 * What the mail says, over the whole card and starting [topInset] down, below the header. It is
 * not scrolled by touch -- a card is read at a glance -- but [scroll] moves it while the card is
 * lifted, see [es.jvbabi.overmail.ui.lift.LiftState], and it goes on behind the header then. A
 * mail that goes on below what shows fades out where the card ends -- or, while the card grows on
 * its way up, where [shownHeight] says it is cut off, so the fade moves with the edge.
 */
@Composable
internal fun CardBody(
    body: StackCardBody,
    scroll: ScrollOffset,
    topInset: Dp,
    modifier: Modifier = Modifier,
    shownHeight: () -> Float? = { null },
) {
    // The paper of the card, see cardSurface.
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .clipToBounds()
            .drawWithContent {
                drawContent()
                // Where the card ends: its box, or on the way up while lifted, where it is cut off.
                val bottom = shownHeight()?.coerceAtMost(size.height) ?: size.height
                // Content that goes on below that, in the box: what is scrolled out below, and
                // what the cut hides.
                if (size.height + scroll.max - scroll.value <= bottom + 0.5f) return@drawWithContent
                val fade = OVERFLOW_FADE.toPx().coerceAtMost(bottom)
                drawRect(
                    brush = Brush.verticalGradient(listOf(paper.copy(alpha = 0f), paper), startY = bottom - fade, endY = bottom),
                    topLeft = Offset(0f, bottom - fade),
                    size = Size(size.width, fade),
                )
            },
    ) {
        when (body) {
            StackCardBody.Loading -> Box(Modifier.fillMaxSize().padding(top = topInset), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is StackCardBody.Html -> EmailPicture(
                picture = body.picture,
                scroll = scroll,
                topInset = topInset,
                modifier = Modifier.fillMaxSize(),
            )
            is StackCardBody.Text -> Text(
                text = body.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.layout { measurable, constraints ->
                    // As tall as the text is, below the header and moved up by the scroll; the
                    // box cuts it off.
                    val inset = topInset.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    scroll.max = (inset + placeable.height - constraints.maxHeight).toFloat()
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(0, inset - scroll.value.roundToInt())
                    }
                },
            )
            StackCardBody.Failed -> Box(Modifier.fillMaxSize().padding(top = topInset), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.home_stack_body_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
