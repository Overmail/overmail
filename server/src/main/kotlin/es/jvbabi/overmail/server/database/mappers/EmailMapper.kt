package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailRecipient
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.ImapAccount
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Maps a row of [Emails] to its domain model. [imapAccount], [sender] and [recipients] have to be
 * resolved by the caller; [Emails.rawContent] is deliberately not read here, see [Email].
 */
fun ResultRow.toEmail(
    imapAccount: ImapAccount,
    sender: EmailUser,
    recipients: List<EmailRecipient>,
): Email = Email(
    id = this[Emails.id].value,
    imapAccount = imapAccount,
    sender = sender,
    senderName = this[Emails.senderName],
    subject = this[Emails.subject],
    sent = this[Emails.sent],
    textContent = this[Emails.textContent],
    htmlContent = this[Emails.htmlContent],
    isRead = this[Emails.isRead],
    isArchived = this[Emails.isArchived],
    lastAiProcessingAt = this[Emails.lastAiProcessingAt],
    recipients = recipients,
)
