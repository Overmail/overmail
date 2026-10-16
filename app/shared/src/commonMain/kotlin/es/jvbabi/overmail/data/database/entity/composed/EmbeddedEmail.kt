package es.jvbabi.overmail.data.database.entity.composed

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import es.jvbabi.overmail.data.database.entity.DbEmail
import es.jvbabi.overmail.data.database.entity.DbEmailAttachment
import es.jvbabi.overmail.data.database.entity.DbEmailLabels
import es.jvbabi.overmail.data.database.entity.DbEmailRecipients
import es.jvbabi.overmail.data.database.entity.DbImapAccount
import es.jvbabi.overmail.data.database.entity.DbLabels
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount
import es.jvbabi.overmail.data.database.entity.DbParticipant
import es.jvbabi.overmail.domain.model.Attachment
import es.jvbabi.overmail.domain.model.Email

data class EmbeddedEmail(
    @Embedded val dbEmail: DbEmail,
    @Relation(
        entity = DbOvermailAccount::class,
        parentColumn = "overmail_account_id",
        entityColumn = "id"
    ) val overmailAccount: DbOvermailAccount,
    @Relation(
        entity = DbImapAccount::class,
        parentColumn = "imap_account_id",
        entityColumn = "id"
    ) val imapAccount: EmbeddedImapAccount,
    @Relation(
        entity = DbParticipant::class,
        parentColumn = "sent_from_participant_id",
        entityColumn = "id"
    ) val senders: List<EmbeddedParticipant>,
    @Relation(
        entity = DbLabels::class,
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = DbEmailLabels::class,
            parentColumn = "email_id",
            entityColumn = "label_id",
        )
    ) val labels: List<EmbeddedLabel>,
    @Relation(
        entity = DbEmailRecipients::class,
        parentColumn = "id",
        entityColumn = "email_id",
    ) val recipients: List<EmbeddedEmailRecipient>,
    @Relation(
        entity = DbEmailAttachment::class,
        parentColumn = "id",
        entityColumn = "email_id"
    ) val attachments: List<DbEmailAttachment>,
) {
    /**
     * A list of one, read as [sender]: Room loads a relation to a single row of more than 999 keys
     * by writing into the map it iterates, which Kotlin/Native's HashMap rejects; a list relation
     * does not (#258).
     */
    val sender: EmbeddedParticipant get() = senders.single()

    fun toModel() = Email(
        id = dbEmail.id,
        overmailAccount = overmailAccount.toModel(),
        imapAccount = imapAccount.toModel(),
        sentBy = sender.toModel(),
        sentAt = dbEmail.sentAt,
        subject = dbEmail.subject,
        preview = dbEmail.preview,
        hasText = dbEmail.hasText,
        hasHtml = dbEmail.hasHtml,
        isRead = dbEmail.isRead,
        archivedState = dbEmail.archivedState,
        labels = labels.map { it.toModel() },
        recipients = recipients.map { it.toModel() },
        attachments = attachments.map { it.toModel() }
    )
}