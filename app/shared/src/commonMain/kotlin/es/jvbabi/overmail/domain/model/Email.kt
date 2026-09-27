package es.jvbabi.overmail.domain.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class Email(
    val id: Uuid,
    val overmailAccount: OvermailAccount,
    val imapAccount: ImapAccount,
    val sentBy: Participant,
    val sentAt: Instant,
    val subject: String?,
    /**
     * How the body begins, as one line. Null while the server has not looked at the body yet,
     * empty for a mail with nothing readable in it.
     */
    val preview: String?,
    /** Whether the body has a plain text part, see `EmailsRepository.getBody`. */
    val hasText: Boolean,
    /** Whether the body has an html part. */
    val hasHtml: Boolean,
    val isRead: Boolean,
    val archivedState: ArchivedState,
    val labels: List<Label>,
    val recipients: List<EmailRecipient>,
)

/** Somebody a mail was addressed to, and in which header field. */
data class EmailRecipient(
    val participant: Participant,
    val type: EmailRecipientType,
)

/** The header field a recipient is in, the server's `EmailRecipientType`. */
enum class EmailRecipientType {
    Recipient,
    Cc,
    Bcc,
}
