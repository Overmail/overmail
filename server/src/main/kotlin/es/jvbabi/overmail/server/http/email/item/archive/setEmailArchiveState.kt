package es.jvbabi.overmail.server.http.email.item.archive

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailArchives
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Where a mail stands: `POST /api/emails/{emailId}/archive`, `/unarchive` and `/spam`.
 *
 * The archive is an event log, so this appends rather than sets -- and only when the mail is not
 * already where the caller wants it, which keeps the log a history of decisions instead of a
 * history of clicks. Idempotent, and only a mail that actually moved is announced.
 */
fun Route.setEmailArchiveState(action: EmailArchiveAction) {
    authenticate {
        post {
            val notifier = call.dependency<MailNotifier>()
            val userId = call.requireAuthenticatedUserId()
            val emailId = call.requireOwnedEmailIdFromUrl()

            val moved = call.database().query {
                // The latest event is the state; a mail with no event at all is in the mailbox.
                // Read through the table rather than through `Email.archiveState`, which needs the
                // entity and therefore the whole mail source.
                val current = EmailArchives
                    .select(EmailArchives.action)
                    .where { EmailArchives.email eq emailId }
                    .orderBy(EmailArchives.createdAt, SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
                    ?.get(EmailArchives.action)
                    ?: EmailArchiveAction.Unarchive

                if (current == action) return@query false

                EmailArchives.insert {
                    it[EmailArchives.email] = emailId
                    it[EmailArchives.action] = action
                    // The reader pressed it, not the agent.
                    it[EmailArchives.createdByAgent] = false
                }
                true
            }

            if (moved) notifier.notifyMailChanged(userId, emailId, movedListings = true)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
