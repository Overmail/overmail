package es.jvbabi.overmail.server.http.email.item.classify

import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Runs the classification over one mail again: `POST /api/emails/{emailId}/classify`.
 *
 * Queued, not done here -- the answer says the run was accepted, and what it produces arrives
 * over the content socket like every other change to the mail.
 */
fun Route.classifyEmailRequest() {
    authenticate {
        post {
            val queue = call.dependency<EmailClassificationQueue>()

            queue.enqueue(emailId = call.requireOwnedEmailIdFromUrl())

            call.respond(HttpStatusCode.Accepted)
        }
    }
}
