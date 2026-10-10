package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.util.Optional
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Attachment
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.truncatedToSecond
import es.jvbabi.overmail.server.jobs.importer.EmailInserter.Result
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid
import es.jvbabi.overmail.kamel.Email as KamelEmail

/** What a decoder puts where a byte sequence made no sense. */
private const val REPLACEMENT_CHARACTER = '\uFFFD'

class EmailInserterImpl(
    private val database: OvermailDatabase
) : EmailInserter {

    private val logger = LoggerFactory.getLogger(EmailInserterImpl::class.java)

    override suspend fun importEmailIntoDatabase(mail: KamelEmail, account: ImapConnection): Result {
        val subject = mail.subject.await()
        val sentAt = try {
            mail.sentAt.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            return Result.Rejected("no readable Date header")
        }
        val from = mail.from.await()
        val sender = from.firstOrNull() ?: return Result.Rejected("no From header")

        // Before the body, not after: downloading it pulls the attachments too.
        if (database.query { isKnown(sentAt, subject, account.id) }) return Result.AlreadyExists

        val to = mail.to.await()
        val cc = mail.cc.await()
        val bcc = mail.bcc.await()
        val content = mail.getContent(includeAttachments = true)
        content.text.warnIfGarbled("text", subject)
        content.html.warnIfGarbled("html", subject)

        return database.query {
            // Again, in the transaction that inserts: the dedup key has no unique index (the
            // subject is `text` and can blow the btree key limit), so no constraint does it for us.
            if (isKnown(sentAt, subject, account.id)) return@query Result.AlreadyExists

            val emailUsers = findOrCreateEmailUsers(
                addresses = (from + to + cc + bcc).map { it.address }.distinct(),
                userId = account.userId,
            )

            val email = Email.new {
                this.imapAccount = ImapAccount[account.id]
                this.sender = EmailUser[emailUsers.getValue(sender.address)]
                this.senderName = sender.name?.take(255)
                this.subject = subject
                this.sent = sentAt.truncatedToSecond()
                this.rawContent = content.raw
                this.textContent = content.text?.takeIf { it.isNotBlank() }
                this.htmlContent = content.html?.takeIf { it.isNotBlank() }
                // Only on import: afterwards the local state is ours, the server's copy must not
                // overwrite it. A mail parsed from its source may carry no flags at all.
                this.isRead = (mail.flagsValue as? Optional.Set)?.value.orEmpty().contains(KamelEmail.Flag.Seen)
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

    /**
     * Mails are recognised by account, send second and subject, see [Emails].
     *
     * A missing subject is compared with `IS NULL`, because NULL never equals NULL and such a mail
     * would import over and over. It also matches `""`, which is what a missing subject was stored
     * as before the column became nullable.
     */
    private fun isKnown(sentAt: Instant, subject: String?, accountId: Uuid): Boolean =
        Emails
            .select(Emails.id)
            .where {
                (Emails.imapAccount eq accountId) and
                    (Emails.sent eq sentAt.truncatedToSecond()) and
                    if (subject == null) Emails.subject.isNull() or (Emails.subject eq "")
                    else Emails.subject eq subject
            }
            .empty()
            .not()

    /**
     * A part that does not decode cleanly comes back with replacement characters rather than
     * failing, because half a mail beats no mail -- but it is worth a line in the log, or a charset
     * the mail library cannot read would quietly turn into a mailbox full of question marks.
     */
    private fun String?.warnIfGarbled(part: String, subject: String?) {
        if (this != null && contains(REPLACEMENT_CHARACTER)) {
            logger.warn("The $part part of ${subject ?: "a mail without a subject"} did not decode cleanly; it is stored as it came out")
        }
    }

    /**
     * Resolves the header addresses to [EmailUsers] ids, inserting the ones this user has not seen
     * before. `insertIgnore` returns null once the address is known -- including the row an
     * importer of another account of the same user just committed -- and the lookup then finds it.
     */
    private fun findOrCreateEmailUsers(addresses: List<String>, userId: Uuid): Map<String, Uuid> =
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
}
