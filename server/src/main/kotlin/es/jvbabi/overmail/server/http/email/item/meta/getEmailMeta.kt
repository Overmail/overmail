package es.jvbabi.overmail.server.http.email.item.meta

import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdsFromUrl
import es.jvbabi.overmail.server.http.webapp.content.EmailMeta
import es.jvbabi.overmail.server.http.webapp.content.loadEmailMeta
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The metadata of a mail: `GET /api/emails/{emailId}`, or of several with `/api/emails/a,b,c`.
 *
 * One id answers the mail, a list answers an array in the order the ids were named -- the comma
 * alone decides, so `a,` is an array of one. The same [EmailMeta] as `QUERY /api/emails/meta`,
 * but addressed: an id that is not there or not the caller's fails the request, see
 * [requireOwnedEmailIdsFromUrl].
 */
fun Route.getEmailMeta() {
    authenticate {
        /**
         * Get the metadata of a mail, or of several.
         *
         * Description: `{emailId}` may be a comma-separated list of at most 100 ids, which answers an array of mails in that order. Every id has to exist and be the current user's, or the request fails.
         *
         * Tag: Emails
         *
         * Responses:
         *   - 200 [EmailMeta] The mail, or an array of them for a list of ids
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] More than 100 ids
         */
        get {
            val ids = call.requireOwnedEmailIdsFromUrl()
            val userId = call.requireAuthenticatedUserId()

            val byId = call.database().query { loadEmailMeta(userId, ids) }.associateBy { it.id }
            // Every id was checked a moment ago; one deleted since is left out rather than failing late.
            val emails = ids.mapNotNull { byId[it] }

            if (',' in call.parameters["emailId"].orEmpty()) call.respond(emails)
            else call.respond(emails.singleOrNull() ?: notFound("email", ids.single().toString()))
        }
    }
}
