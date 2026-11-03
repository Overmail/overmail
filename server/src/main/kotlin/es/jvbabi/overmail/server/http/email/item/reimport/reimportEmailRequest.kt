package es.jvbabi.overmail.server.http.email.item.reimport

import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdFromUrl
import es.jvbabi.overmail.server.jobs.reimport.EmailReimportQueue
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Imports one mail again from its mailbox: `POST /api/emails/{emailId}/reimport`.
 *
 * Queued, not done here: finding the mail reads every folder of its inbox. What it changes arrives
 * over the content socket like every other change to the mail.
 */
fun Route.reimportEmailRequest() {
    authenticate {
        /**
         * Import a mail again from its mailbox.
         *
         * Description: Queued; the mail is looked for in every folder of its inbox by subject and date and its content replaced, keeping its id, labels, read state and shares.
         *
         * Tag: Emails
         *
         * Responses:
         *   - 202 The re-import is queued
         */
        post {
            call.dependency<EmailReimportQueue>().enqueue(emailId = call.requireOwnedEmailIdFromUrl())
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
