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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.ui.components.EmailHtml
import es.jvbabi.overmail.ui.components.LabelBadge
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

    /** A mail with an html part, which is what is shown of it then. */
    data class Html(val html: String) : StackCardBody

    /** A mail with only a text part. */
    data class Text(val text: String) : StackCardBody

    data object Failed : StackCardBody
}

/** What a card says of its mail before the mail itself: the subject, who sent it, its labels. */
@Composable
internal fun CardHeader(email: Email, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 24.dp).padding(horizontal = 24.dp)) {
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
 * What the mail says, in whatever room the card has left, down to the card's edge. It does not
 * scroll: a card is read at a glance, and a mail longer than the card fades out where the card
 * ends.
 */
@Composable
internal fun CardBody(body: StackCardBody, modifier: Modifier = Modifier) {
    // Per body: a mail that did not fit says nothing about the next one.
    var overflows by remember(body) { mutableStateOf(false) }
    // The paper of the card, see cardSurface.
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 24.dp, top = 24.dp, end = 24.dp)
            .clipToBounds()
            .drawWithContent {
                drawContent()
                if (!overflows) return@drawWithContent
                val fade = OVERFLOW_FADE.toPx().coerceAtMost(size.height)
                drawRect(
                    brush = Brush.verticalGradient(listOf(paper.copy(alpha = 0f), paper), startY = size.height - fade, endY = size.height),
                    topLeft = Offset(0f, size.height - fade),
                )
            },
    ) {
        when (body) {
            StackCardBody.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            is StackCardBody.Html -> EmailHtml(
                html = body.html,
                modifier = Modifier.fillMaxSize(),
                onOverflowChange = { overflows = it },
            )
            is StackCardBody.Text -> Text(
                text = body.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Clip,
                onTextLayout = { overflows = it.didOverflowHeight },
            )
            StackCardBody.Failed -> Text(
                text = stringResource(Res.string.home_stack_body_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
