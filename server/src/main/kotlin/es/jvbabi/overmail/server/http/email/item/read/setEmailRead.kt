package es.jvbabi.overmail.server.http.email.item.read

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Whether the reader has seen the mail: `POST /api/emails/{emailId}/read` and `/unread`.
 *
 * Idempotent, and it answers the same either way -- a mail that is already in the state the
 * caller asked for is what the caller wanted. Only a mail that actually changed is announced, so
 * nothing on screen is re-read for a request that wrote nothing.
 */
fun Route.setEmailRead(isRead: Boolean) {
    authenticate {
        post {
            val mailNotifier = call.dependency<MailNotifier>()
            val userId = call.requireAuthenticatedUserId()
            val emailId = call.requireOwnedEmailIdFromUrl()

            // Columns rather than the entity: this write touches one flag, and loading an Email
            // reads its raw source with it. The state it is in is part of the statement, so the
            // row count is the answer to whether anything changed.
            val changed = call.database().query {
                Emails.update({
                    (Emails.id eq emailId) and (Emails.isRead neq isRead)
                }) { it[Emails.isRead] = isRead } > 0
            }

            // Nothing moved: the mail sits where it sat, it only reads differently now.
            if (changed) mailNotifier.notifyMailChanged(userId, emailId, movedListings = false)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
