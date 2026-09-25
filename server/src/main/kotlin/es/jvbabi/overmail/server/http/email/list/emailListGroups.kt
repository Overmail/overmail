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
        /**
         * Get the groups a listing is cut into.
         *
         * Description: One count per group that holds mail, keys outermost first, in no particular order. Without `by` it is one group over everything. A filter parameter that is absent restricts nothing; one that is present but empty lets nothing through.
         *
         * Tag: Listing
         *
         * Query parameters:
         *   - by [String] Comma-separated groupings, outermost first: `date_smart`, `year`, `month`, `day`, `sender`, `imap_account`, `read`, `archived`
         *   - archived_state [String] Comma-separated states that pass: `Archive`, `Unarchive`, `Spam`
         *   - read_state [Boolean] `true` for read mails only, `false` for unread ones only
         *   - imap_account_ids [String] Comma-separated ids of the inboxes the mails came in through
         *   - sent_by [String] Comma-separated sender ids; `self` stands for the user's own addresses
         *   - sent_to [String] Comma-separated recipient ids; `self` stands for the user's own addresses
         *   - has_labels [String] Comma-separated label ids; a mail has to carry at least one of them
         *
         * Responses:
         *   - 200 [EmailGroupsResponse] The groups
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A parameter that cannot be read
         */
        get {
            val parameters = call.request.queryParameters
            val groupings = mailGroupings(parameters)
            val filter = mailFilter(parameters)
            val userId = call.requireAuthenticatedUserId()

            call.respond(call.database().query { listingGroups(userId, filter, groupings) })
        }
    }
}
