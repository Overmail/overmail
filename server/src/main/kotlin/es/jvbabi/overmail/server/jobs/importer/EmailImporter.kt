package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.truncatedToSecond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.time.Instant
import kotlin.uuid.Uuid
import es.jvbabi.overmail.kamel.Email as KamelEmail

/**
 * Stores a mail and the addresses in its headers, and nothing else: no preview, no attachments,
 * no classification and no notification. Whoever calls this decides what follows from
 * [Result.Imported].
 */
class EmailImporter : AbstractEmailImporter, KoinComponent {
    private val database by inject<OvermailDatabase>()

    /**
     * @throws IllegalArgumentException if the file is not a message or has no `From` header
     * @throws IllegalStateException if the message has no readable `Date` header
     */
    override suspend fun importEmailFile(emailFile: File, imapAccount: ImapAccount): Result {
        val mail = withContext(Dispatchers.IO) { KamelEmail.parse(emailFile.readBytes()) }

        val subject = mail.subject.await()
        val sentAt = mail.sentAt.await()
        val from = mail.from.await()
        val to = mail.to.await()
        val cc = mail.cc.await()
        val bcc = mail.bcc.await()
        val content = mail.getContent()

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
