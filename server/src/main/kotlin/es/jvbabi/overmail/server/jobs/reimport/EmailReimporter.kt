package es.jvbabi.overmail.server.jobs.reimport

import es.jvbabi.overmail.kamel.ImapFolder
import es.jvbabi.overmail.kamel.MailClient
import es.jvbabi.overmail.kamel.MailFolder
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Attachments
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.truncatedToSecond
import es.jvbabi.overmail.server.jobs.importer.EmailPreviewGenerator
import es.jvbabi.overmail.server.jobs.importer.ImapConnection
import es.jvbabi.overmail.server.jobs.importer.findOrCreateEmailUsers
import es.jvbabi.overmail.server.jobs.importer.imapClient
import es.jvbabi.overmail.server.jobs.importer.oauthGrantStates
import es.jvbabi.overmail.server.jobs.importer.toConnection
import es.jvbabi.overmail.server.jobs.importer.writeAttachments
import es.jvbabi.overmail.server.jobs.importer.writeRecipients
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.slf4j.LoggerFactory
import kotlin.time.Instant
import kotlin.uuid.Uuid
import es.jvbabi.overmail.kamel.Email as KamelEmail

/**
 * Imports one stored mail again from its mailbox, for a mail that came in wrong.
 *
 * The mail is looked for in every folder of its inbox, by what the importer recognises a mail by:
 * send second and subject. Each folder is searched on the server, so only candidates are fetched. The first match replaces what is stored -- sender, recipients, both
 * bodies, the attachments and the source -- in one transaction. The row itself stays, and with it
 * the id, the labels, the read state and every share; the subject and the date are what it was
 * found by and so cannot have changed.
 *
 * It does not go through the import pipeline: nothing is new about the mail, so it is neither
 * classified nor pushed. The preview is worked out again, and the listings are told.
 */
class EmailReimporter(
    private val database: OvermailDatabase,
    private val previewGenerator: EmailPreviewGenerator,
    private val mailNotifier: MailNotifier,
    private val connect: (ImapConnection) -> MailClient = ::imapClient,
) {
    private val logger = LoggerFactory.getLogger(EmailReimporter::class.java)

    sealed interface Result {
        /** Found in [folder] and stored again. */
        data class Reimported(val folder: String) : Result

        /** No folder of the inbox holds it any more. */
        data object NotFound : Result

        /** The mail or its inbox was deleted in the meantime. */
        data object Gone : Result
    }

    /** What the mail is found by, and where to look, read in one go. */
    private data class Target(
        val userId: Uuid,
        val subject: String?,
        val sent: Instant,
        val account: ImapConnection,
    )

    suspend fun reimport(emailId: Email.Id): Result {
        val target = database.query {
            val email = Email.findById(emailId) ?: return@query null
            val account = ImapAccount.findById(email.imapAccount.id) ?: return@query null
            Target(
                userId = account.user.id.value,
                subject = email.subject,
                sent = email.sent,
                account = account.toConnection(oauthGrantStates(account.id.value)[account.id.value]),
            )
        } ?: return Result.Gone

        val client = connect(target.account)
        try {
            // The inbox first: it is where most mails are, and the search stops at the first hit.
            val folders = client.getFolders().sortedBy { it.specialType != ImapFolder.SpecialType.INBOX }
            for (folder in folders) {
                if (replaceFrom(folder, emailId, target)) {
                    previewGenerator.generatePreview(database.query { Email[emailId] })
                    mailNotifier.notifyMailChanged(target.userId, emailId, movedListings = false)
                    return Result.Reimported(folder.fullName)
                }
            }
            return Result.NotFound
        } finally {
            (client as? AutoCloseable)?.close()
        }
    }

    /**
     * Asks [folder] for the mail and, on a match, stores it again.
     *
     * The server searches by subject and by the days around the send date -- whole days in the
     * time zone the `Date` header is written in, which can be a day off UTC -- and only those
     * candidates are fetched and compared to the second. A mail without a subject is searched by
     * the days alone: IMAP cannot ask for an empty one.
     *
     * The body is downloaded while the fetch is still being collected, as the importer does it:
     * the mail is bound to the folder's connection, and `take(1)` ends the fetch once it is through.
     */
    private suspend fun replaceFrom(folder: MailFolder, emailId: Email.Id, target: Target): Boolean {
        val day = target.sent.toLocalDateTime(TimeZone.UTC).date
        val candidates = folder.search {
            target.subject?.takeIf { it.isNotEmpty() }?.let { subject(it) }
            sentSince(day.minus(1, DateTimeUnit.DAY))
            sentBefore(day.plus(2, DateTimeUnit.DAY))
        }
        if (candidates.isEmpty()) return false

        var replaced = false
        folder.getMails {
            uids(candidates)
            envelope = true
            uid = true
            onUnreadable { logger.debug("Skipping an unreadable mail of ${folder.fullName} while looking for $emailId") }
        }
            .filter { it.matches(target) }
            .take(1)
            .collect { mail -> replaced = replace(emailId, mail, target) }
        return replaced
    }

    private suspend fun KamelEmail.matches(target: Target): Boolean {
        val sentAt = runCatching { sentAt.await() }.getOrNull() ?: return false
        if (sentAt.truncatedToSecond() != target.sent) return false
        val subject = subject.await()
        // A missing subject was stored as "" before the column became nullable, see EmailInserterImpl.
        return if (target.subject.isNullOrEmpty()) subject.isNullOrEmpty() else subject == target.subject
    }

    private suspend fun replace(emailId: Email.Id, mail: KamelEmail, target: Target): Boolean {
        val sender = mail.from.await().firstOrNull() ?: return false
        val to = mail.to.await()
        val cc = mail.cc.await()
        val bcc = mail.bcc.await()
        val content = mail.getContent(includeAttachments = true)

        // Whole or not at all, like an import: the old content is only dropped together with
        // writing the new one.
        return withContext(NonCancellable) {
            database.query {
                val email = Email.findById(emailId) ?: return@query false
                val emailUsers = findOrCreateEmailUsers(
                    addresses = (mail.from.await() + to + cc + bcc).map { it.address }.distinct(),
                    userId = target.userId,
                )

                email.sender = EmailUser[emailUsers.getValue(sender.address)]
                email.senderName = sender.name?.take(255)
                email.rawContent = content.raw
                email.textContent = content.text?.takeIf { it.isNotBlank() }
                email.htmlContent = content.html?.takeIf { it.isNotBlank() }

                EmailRecipients.deleteWhere { EmailRecipients.email eq emailId }
                Attachments.deleteWhere { Attachments.email eq emailId }
                writeRecipients(email, to, cc, bcc, emailUsers)
                writeAttachments(email, content)
                true
            }
        }
    }
}
