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
        /**
         * Get every mail id of one group.
         *
         * Description: What selecting a whole group needs. Fewer keys than `by` has levels name a group further up. Newest first and at most 10000 ids; `total` says when the answer was cut. A filter parameter that is absent restricts nothing; one that is present but empty lets nothing through.
         *
         * Tag: Listing
         *
         * Query parameters:
         *   - by [String] Comma-separated groupings, outermost first: `date_smart`, `year`, `month`, `day`, `sender`, `imap_account`, `read`, `archived`
         *   - group [String] Comma-separated keys of one group, outermost first, as `/api/emails/list/groups` answered them
         *   - archived_state [String] Comma-separated states that pass: `Archive`, `Unarchive`, `Spam`
         *   - read_state [Boolean] `true` for read mails only, `false` for unread ones only
         *   - imap_account_ids [String] Comma-separated ids of the inboxes the mails came in through
         *   - sent_by [String] Comma-separated sender ids; `self` stands for the user's own addresses
         *   - sent_to [String] Comma-separated recipient ids; `self` stands for the user's own addresses
         *   - has_labels [String] Comma-separated label ids; a mail has to carry at least one of them
         *
         * Responses:
         *   - 200 [EmailIdsResponse] The ids
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A parameter that cannot be read
         */
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
