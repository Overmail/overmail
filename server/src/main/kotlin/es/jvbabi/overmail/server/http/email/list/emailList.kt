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
        /**
         * Get a page of one group of the listing.
         *
         * Description: Ids in order and the length of the group, nothing else. `by` has to be what the groups were read with; without `group` this pages through the whole listing. A filter parameter that is absent restricts nothing; one that is present but empty lets nothing through.
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
         *   - sort [String] `date`, `sender` or `subject`, with `:r` appended to reverse it; defaults to `date`, newest first
         *   - limit [Int] How many ids to answer, 1 to 500; defaults to 100
         *   - offset [Int] Where in the group the page starts; defaults to 0
         *
         * Responses:
         *   - 200 [EmailListResponse] The page
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A parameter that cannot be read
         */
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
