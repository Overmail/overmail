package es.jvbabi.overmail.server.http.email.meta

import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.email.bulk.BulkEmailsRequest
import es.jvbabi.overmail.server.http.email.bulk.MAX_BULK_IDS
import es.jvbabi.overmail.server.http.webapp.content.EmailMeta
import es.jvbabi.overmail.server.http.webapp.content.loadEmailMeta
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.query
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The whole metadata of a list of mails: `QUERY /api/emails/meta` with `{"ids": [...]}`.
 *
 * What a client with a database of its own fills it from -- the same [EmailMeta] the content
 * socket and the change stream send, so there is one shape to store whichever way a mail came in.
 * `GET /api/emails?ids=` answers a lighter one for a cache that only draws rows.
 *
 * QUERY rather than GET because the ids are a body, a stretch of mailbox that does not fit in a
 * url; rather than POST because it changes nothing, so a client may send it again as it likes.
 */
fun Route.emailsMeta() {
    authenticate {
        /**
         * Get the metadata of a list of mails.
         *
         * Description: Unknown ids and mails of somebody else are left out of the answer rather than failing it, in no particular order.
         *
         * Tag: Emails
         *
         * Body: [es.jvbabi.overmail.server.http.email.bulk.BulkEmailsRequest] The mails, at most 500
         *
         * Responses:
         *   - 200 [EmailsMetaResponse] The mails among them that exist and are the current user's
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] More than 500 ids
         */
        query {
            val ids = call.receive<BulkEmailsRequest>().ids.distinct()
            if (ids.size > MAX_BULK_IDS) {
                invalidRequest("ids", "are more than $MAX_BULK_IDS mails", ids.size.toString())
            }

            val userId = call.requireAuthenticatedUserId()

            call.respond(EmailsMetaResponse(call.database().query { loadEmailMeta(userId, ids) }))
        }
    }
}

@Serializable
data class EmailsMetaResponse(
    @SerialName("emails") val emails: List<EmailMeta>,
)
