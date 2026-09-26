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
