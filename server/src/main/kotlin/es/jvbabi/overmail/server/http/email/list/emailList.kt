package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.intQueryParameter
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The mails of one group of the listing: `GET /api/emails/list?by=date_smart&group=2026-09-14`.
 *
 * Ids and a length, nothing else -- see [listingPage], which the listing socket answers from as
 * well. What it costs is the offset, which the database walks; a group is a day or a
 * correspondent of mail, and the ungrouped listing is the one case where that walk is the whole
 * mailbox.
 *
 * `by` says what the keys in `group` mean and has to be the list the groups were read with;
 * without either, this is the whole listing as one group. Which mails are in it at all is the
 * filter, see [MailFilter]; what orders them inside the group is `sort`, see [MailSorting].
 */
fun Route.emailList() {
    authenticate {
        get {
            val parameters = call.request.queryParameters
            val groupings = mailGroupings(parameters)
            val group = mailGroup(parameters, groupings)
            val filter = mailFilter(parameters)
            val sorting = mailSorting(parameters)
            val limit = call.intQueryParameter("limit", default = DEFAULT_LIMIT, range = 1..MAX_LIMIT)
            val offset = call.intQueryParameter("offset", default = 0, range = 0..1_000_000)

            val userId = call.requireAuthenticatedUserId()

            call.respond(call.database().query { listingPage(userId, filter, group, sorting, limit, offset) })
        }
    }
}
