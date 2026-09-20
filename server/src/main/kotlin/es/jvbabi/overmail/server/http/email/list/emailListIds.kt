package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Every mail of one group: `GET /api/emails/list/ids?by=date_smart,sender&group=2026-09-14`.
 *
 * What `GET /api/emails/list` answers a screen at a time, this answers in one go -- which is what
 * ticking a whole stretch of the table needs, headers included: picking the group at the top of a
 * day is picking the mails under it, and the client only holds the ids of the rows it has drawn.
 * See [listingIds].
 *
 * Fewer keys than `by` has levels is a group further up the tree: `group=2026-09-14` under
 * `by=date_smart,sender` is that day whoever sent it, which is exactly what ticking the outer
 * header means.
 *
 * Stays an endpoint while the listing itself moved onto a socket: this is an answer to a click,
 * not something a screen keeps up to date, and it is the one place a client asks for mails it has
 * not drawn.
 *
 * Which mails are in it at all is the filter, see [MailFilter] -- the same one the listing was
 * drawn with, or the stretch would hold mails the rows never showed.
 */
fun Route.emailListIds() {
    authenticate {
        get {
            val parameters = call.request.queryParameters
            val groupings = mailGroupings(parameters)
            val group = mailGroup(parameters, groupings)
            val filter = mailFilter(parameters)

            val userId = call.requireAuthenticatedUserId()

            call.respond(call.database().query { listingIds(userId, filter, group) })
        }
    }
}
