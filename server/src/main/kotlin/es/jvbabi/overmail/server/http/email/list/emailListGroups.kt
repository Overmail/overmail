package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * What the listing is cut into: `GET /api/emails/list/groups?by=date_smart,sender`.
 *
 * Counts, not ids -- the groups are the *shape* of the list, so a windowed table can lay out its
 * headers and size its scrollbar before a single mail is loaded. See [listingGroups], which the
 * listing socket answers from as well.
 *
 * Which mails it counts is the filter, the same one the listing itself is drawn with, see
 * [MailFilter]. A stretch that counted mails the rows do not show would be a header over the
 * wrong number.
 *
 * The rows come in no particular order and carry no labels: what a group is called and where it
 * goes is the client's, which is also the only side that can fold days into "today" or put
 * senders in the order of the names it resolved. See [MailGroupingKind].
 *
 * No `by=` at all is one group over everything, as an ungrouped listing still has a shape.
 */
fun Route.emailListGroups() {
    authenticate {
        get {
            val parameters = call.request.queryParameters
            val groupings = mailGroupings(parameters)
            val filter = mailFilter(parameters)
            val userId = call.requireAuthenticatedUserId()

            call.respond(call.database().query { listingGroups(userId, filter, groupings) })
        }
    }
}
