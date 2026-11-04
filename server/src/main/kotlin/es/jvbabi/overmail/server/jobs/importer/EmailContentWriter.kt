package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.models.Attachment
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.util.embeddedContentId
import es.jvbabi.overmail.server.util.referencedContentIds
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid
import es.jvbabi.overmail.kamel.Email as KamelEmail
import es.jvbabi.overmail.kamel.EmailUser as KamelEmailUser

/*
 * What a stored mail is made of besides its own row, written the same way by the import
 * ([EmailInserterImpl]) and by a re-import ([es.jvbabi.overmail.server.jobs.reimport.EmailReimporter]).
 * Each of these runs inside the caller's transaction.
 */

/**
 * Resolves the header addresses to [EmailUsers] ids, inserting the ones this user has not seen
 * before. `insertIgnore` returns null once the address is known -- including the row an importer
 * of another account of the same user just committed -- and the lookup then finds it.
 */
internal fun findOrCreateEmailUsers(addresses: List<String>, userId: Uuid): Map<String, Uuid> =
    addresses.associateWith { address ->
        EmailUsers.insertIgnoreAndGetId {
            it[user] = userId
            it[EmailUsers.address] = address
        }?.value
            ?: EmailUsers
                .select(EmailUsers.id)
                .where { (EmailUsers.user eq userId) and (EmailUsers.address eq address) }
                .single()[EmailUsers.id].value
    }

/** [emailUsers] has to hold every address of the three fields, see [findOrCreateEmailUsers]. */
internal fun writeRecipients(
    email: Email,
    to: Set<KamelEmailUser>,
    cc: Set<KamelEmailUser>,
    bcc: Set<KamelEmailUser>,
    emailUsers: Map<String, Uuid>,
) {
    listOf(
        to to EmailRecipientType.RECIPIENT,
        cc to EmailRecipientType.CC,
        bcc to EmailRecipientType.BCC,
    )
        .flatMap { (users, type) ->
            users.map { NewRecipient(emailUsers.getValue(it.address), it.name, type) }
        }
        // The unique index is (mail, address, field), so an address listed twice in the same
        // field has to collapse into one row. Sorting first lets the named entry win.
        .sortedBy { it.name == null }
        .distinctBy { it.emailUserId to it.type }
        .forEach { recipient ->
            EmailRecipients.insert {
                it[EmailRecipients.email] = email.id
                it[emailUser] = recipient.emailUserId
                it[name] = recipient.name?.take(255)
                it[type] = recipient.type
            }
        }
}

/** After [Email.htmlContent] is set: which attachment is embedded is read off the html. */
internal fun writeAttachments(email: Email, content: KamelEmail.Content) {
    val referencedContentIds = referencedContentIds(email.htmlContent)
    content.attachments.forEachIndexed { index, attachment ->
        val contentId = embeddedContentId(attachment.contentId, attachment.contentType, referencedContentIds)
        Attachment.new {
            this.email = email
            this.filename = (attachment.fileName ?: "attachment-$index").take(255)
            this.contentType = attachment.contentType.take(255)
            this.data = ExposedBlob(attachment.data)
            this.size = attachment.data.size.toLong()
            this.contentId = contentId
            this.isEmbedded = contentId != null
        }
    }
}

private data class NewRecipient(
    val emailUserId: Uuid,
    val name: String?,
    val type: EmailRecipientType,
)
