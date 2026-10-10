package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Attachment
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.truncatedToSecond
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid
import es.jvbabi.overmail.kamel.Email as KamelEmail

class EmailInserterImpl(
    private val database: OvermailDatabase
) : EmailInserter {

    /**
     * @throws IllegalArgumentException if the mail has no `From` header
     * @throws IllegalStateException if the mail has no readable `Date` header
     */
    override suspend fun importEmailIntoDatabase(
        mail: KamelEmail,
        imapAccount: ImapAccount,
        flags: Set<KamelEmail.Flag>,
    ): Result {
        val subject = mail.subject.await()
        val sentAt = mail.sentAt.await()
        val from = mail.from.await()
        val to = mail.to.await()
        val cc = mail.cc.await()
        val bcc = mail.bcc.await()
        val content = mail.getContent(includeAttachments = true)

        val sender = requireNotNull(from.firstOrNull()) { "Mail without a From header: $subject" }

        // The account comes out of a transaction that is over, so the owner is read off the row
        // it was loaded with instead of through the reference.
        val userId = imapAccount.readValues[ImapAccounts.user]

        return database.query {
            // Check and insert share this transaction. The dedup key has no unique index (the
            // subject is `text` and can blow the btree key limit), so the constraint cannot do it
            // for us.
            if (isKnown(sentAt, subject, imapAccount)) return@query Result.AlreadyExists

            val emailUsers = findOrCreateEmailUsers(
                addresses = (from + to + cc + bcc).map { it.address }.distinct(),
                userId = userId,
            )

            val email = Email.new {
                this.imapAccount = imapAccount
                this.sender = EmailUser[emailUsers.getValue(sender.address)]
                this.senderName = sender.name?.take(255)
                this.subject = subject
                this.sent = sentAt.truncatedToSecond()
                this.rawContent = content.raw
                this.textContent = content.text?.takeIf { it.isNotBlank() }
                this.htmlContent = content.html?.takeIf { it.isNotBlank() }
                this.isRead = KamelEmail.Flag.Seen in flags
            }

            listOf(
                to to EmailRecipientType.RECIPIENT,
                cc to EmailRecipientType.CC,
                bcc to EmailRecipientType.BCC,
            )
                .flatMap { (users, type) ->
                    users.map { NewRecipient(emailUsers.getValue(it.address), it.name, type) }
                }
                // The unique index is (mail, address, field), so an address listed twice in the
                // same field has to collapse into one row. Sorting first lets the named entry win.
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

            content.attachments.forEachIndexed { index, attachment ->
                Attachment.new {
                    this.email = email
                    this.filename = (attachment.fileName ?: "attachment-$index").take(255)
                    this.contentType = attachment.contentType.take(255)
                    this.data = ExposedBlob(attachment.data)
                    this.size = attachment.data.size.toLong()
                }
            }

            Result.Imported(email)
        }
    }

    /** Mails are recognised by account, send second and subject, see [Emails]. */
    private fun isKnown(sentAt: Instant, subject: String?, imapAccount: ImapAccount): Boolean =
        Emails
            .select(Emails.id)
            .where {
                (Emails.imapAccount eq imapAccount.id) and
                    (Emails.sent eq sentAt.truncatedToSecond()) and
                    (Emails.subject eq subject)
            }
            .empty()
            .not()

    /**
     * Resolves the header addresses to [EmailUsers] ids, inserting the ones this user has not seen
     * before. `insertIgnore` returns null once the address is known -- including the row an
     * importer of another account of the same user just committed -- and the lookup then finds it.
     */
    private fun findOrCreateEmailUsers(addresses: List<String>, userId: EntityID<Uuid>): Map<String, Uuid> =
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

    private data class NewRecipient(
        val emailUserId: Uuid,
        val name: String?,
        val type: EmailRecipientType,
    )

    sealed class Result {
        data object AlreadyExists : Result()

        /** [email] is out of its transaction: its own columns are readable, its references are not. */
        data class Imported(val email: Email) : Result()
    }
}
