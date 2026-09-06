package es.jvbabi.overmail.server.http.email.bulk

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailArchives
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Where a whole selection stands: `POST /api/emails/bulk/archive` and `/bulk/unarchive`, with
 * `{"ids": [...]}`.
 *
 * The archive is an event log, so this appends one event per mail that is not already where the
 * caller wants it -- which keeps the log a history of decisions rather than of clicks, exactly as
 * the single-mail route does. Where each of them stands is read in one query and folded here, so a
 * selection costs two statements and not two per mail.
 *
 * Mails that belong to somebody else are left out without a word about them.
 */
fun Route.setEmailsArchiveState(action: EmailArchiveAction) {
    authenticate {
        post {
            val mailNotifier = call.dependency<MailNotifier>()
            val userId = call.requireAuthenticatedUserId()

            val ids = call.receive<BulkEmailsRequest>().ids.distinct()
            if (ids.size > MAX_BULK_IDS) {
                invalidRequest("ids", "are more than $MAX_BULK_IDS mails", ids.size.toString())
            }

            val moved = if (ids.isEmpty()) emptyList() else call.database().query {
                // Which of them are this user's mails, and the only ones that may be touched.
                val owned = Emails
                    .leftJoin(ImapAccounts)
                    .select(Emails.id)
                    .where { (ImapAccounts.user eq userId) and (Emails.id inList ids) }
                    .map { row -> row[Emails.id].value }

                // The latest event of a mail is where it stands. Oldest first, so the last one
                // read for a mail is the one that counts; a mail with no event is in the mailbox.
                val current = EmailArchives
                    .select(EmailArchives.email, EmailArchives.action, EmailArchives.createdAt)
                    .where { EmailArchives.email inList owned }
                    .orderBy(EmailArchives.createdAt to SortOrder.ASC)
                    .associate { row -> row[EmailArchives.email].value to row[EmailArchives.action] }

                val moving = owned.filter { emailId ->
                    (current[emailId] ?: EmailArchiveAction.Unarchive) != action
                }

                EmailArchives.batchInsert(moving) { emailId ->
                    this[EmailArchives.email] = emailId
                    this[EmailArchives.action] = action
                    // The reader pressed it, not the agent.
                    this[EmailArchives.createdByAgent] = false
                }

                moving
            }

            // After the transaction committed. Every one of these moved the listings it was in,
            // which is what a table holding positions has to hear about.
            moved.forEach { emailId ->
                mailNotifier.notifyMailChanged(userId, emailId, movedListings = true)
            }

            call.respond(HttpStatusCode.OK, BulkEmailsResponse(changed = moved.size))
        }
    }
}
