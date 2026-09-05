package es.jvbabi.overmail.server.http.email.item.labels

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.attachLabelToEmail
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdFromUrl
import es.jvbabi.overmail.server.http.api.requireOwnedLabelFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere

/**
 * Whether a label hangs on a mail: `POST /api/emails/{emailId}/labels/{labelId}` puts it there,
 * `DELETE` takes it off again.
 *
 * The pair is the whole address of it -- `EmailLabels` is unique on (email, label) -- so no
 * assignment id appears in this api, and the label id a client already holds is all it needs.
 *
 * Both are idempotent and answer the same either way: what the caller asked for is how the mail
 * stands afterwards. Only a mail that actually changed is announced.
 */
fun Route.attachEmailLabel() {
    authenticate {
        post {
            val mailNotifier = call.dependency<MailNotifier>()
            val userId = call.requireAuthenticatedUserId()
            val emailId = call.requireOwnedEmailIdFromUrl()
            val labelId = call.requireOwnedLabelFromUrl().id.value

            val attached = call.database().query { attachLabelToEmail(emailId, labelId) }

            // A label changes what a row shows, not where it sits.
            if (attached) mailNotifier.notifyMailChanged(userId, emailId, movedListings = false)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

fun Route.detachEmailLabel() {
    authenticate {
        delete {
            val mailNotifier = call.dependency<MailNotifier>()
            val userId = call.requireAuthenticatedUserId()
            val emailId = call.requireOwnedEmailIdFromUrl()
            val labelId = call.requireOwnedLabelFromUrl().id.value

            val detached = call.database().query {
                EmailLabels.deleteWhere {
                    (EmailLabels.email eq emailId) and (EmailLabels.label eq labelId)
                } > 0
            }

            if (detached) mailNotifier.notifyMailChanged(userId, emailId, movedListings = false)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
