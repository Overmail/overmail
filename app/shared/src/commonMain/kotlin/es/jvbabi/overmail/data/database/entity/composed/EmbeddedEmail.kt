package es.jvbabi.overmail.data.database.entity.composed

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import es.jvbabi.overmail.data.database.entity.DbEmail
import es.jvbabi.overmail.data.database.entity.DbEmailLabels
import es.jvbabi.overmail.data.database.entity.DbEmailRecipients
import es.jvbabi.overmail.data.database.entity.DbImapAccount
import es.jvbabi.overmail.data.database.entity.DbLabels
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount
import es.jvbabi.overmail.data.database.entity.DbParticipant
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
    ) val sender: EmbeddedParticipant,
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
) {
    fun toModel() = Email(
        id = dbEmail.id,
        overmailAccount = overmailAccount.toModel(),
        imapAccount = imapAccount.toModel(),
        sentBy = sender.toModel(),
        sentAt = dbEmail.sentAt,
        subject = dbEmail.subject,
        isRead = dbEmail.isRead,
        archivedState = dbEmail.archivedState,
        labels = labels.map { it.toModel() },
        recipients = recipients.map { it.toModel() },
    )
}