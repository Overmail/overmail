package es.jvbabi.overmail.page.email.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowBendDownRight
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.EmailRecipientType
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.formatDateTime
import es.jvbabi.overmail.ui.components.ParticipantAvatar
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.email_participants_bcc
import overmail.app.shared.generated.resources.email_participants_cc
import overmail.app.shared.generated.resources.email_participants_to
import overmail.app.shared.generated.resources.email_participants_you

/** The header fields in the order a header is read, with what each is called. */
private val FIELDS = listOf(
    EmailRecipientType.Recipient to Res.string.email_participants_to,
    EmailRecipientType.Cc to Res.string.email_participants_cc,
    EmailRecipientType.Bcc to Res.string.email_participants_bcc,
)

/**
 * Who the mail is between, the web app's `Participants`: the sender with their address and when
 * it was sent, then one line per header field that has anybody in it.
 */
@Composable
fun EmailParticipants(email: Email, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ParticipantAvatar(
                participant = email.sentBy,
                size = 40.dp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (email.sentBy.name != null) Text(
                    text = email.sentBy.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = email.sentBy.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = sentAtFull(email),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val ownAddresses = remember(email) {
            setOf(email.overmailAccount.email.lowercase(), email.imapAccount.username.lowercase())
        }
        FIELDS.forEach { (type, label) ->
            val people = email.recipients.filter { it.type == type }.map { it.participant }
            if (people.isNotEmpty()) RecipientLine(label = label, people = people, ownAddresses = ownAddresses)
        }
    }
}

@Composable
private fun RecipientLine(label: StringResource, people: List<Participant>, ownAddresses: Set<String>) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = PhIcons.Regular.ArrowBendDownRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            people.forEachIndexed { index, person ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ParticipantAvatar(participant = person, size = 20.dp, modifier = Modifier.clip(RoundedCornerShape(4.dp)))
                    // The reader is not a name in their own mail.
                    val name = if (person.email.lowercase() in ownAddresses) stringResource(Res.string.email_participants_you) else person.displayName
                    Text(
                        text = if (index == people.lastIndex) name else "$name,",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The day and the time, the year only when it is another one. */
@Composable
private fun sentAtFull(email: Email): String {
    val languageTag = Locale.current.toLanguageTag()
    return remember(email.sentAt, languageTag) { formatDateTime(email.sentAt, "dMMMyjjmm", languageTag) }
}
