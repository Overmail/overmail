package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/**
 * As many ids as one answer here carries. A group of a listing is a day or a correspondent of
 * mail, so this is far above what anybody has in one -- it is here so a request cannot be made to
 * stream a mailbox, not to page. [EmailIdsResponse.total] says when it bit.
 */
private const val MAX_IDS = 10_000

/**
 * Every mail of one group: `GET /api/emails/list/ids?by=date_smart,sender&group=2026-09-14`.
 *
 * What `GET /api/emails/list` answers a screen at a time, this answers in one go -- which is what
 * ticking a whole stretch of the table needs, headers included: picking the group at the top of a
 * day is picking the mails under it, and the client only holds the ids of the rows it has drawn.
 *
 * Fewer keys than `by` has levels is a group further up the tree: `group=2026-09-14` under
 * `by=date_smart,sender` is that day whoever sent it, which is exactly what ticking the outer
 * header means.
 *
 * Which mails are in it at all is the filter, see [MailFilter] -- the same one the listing was
 * drawn with, or the stretch would hold mails the rows never showed.
 */
fun Route.emailListIds() {
    authenticate {
        get {
            val groupings = call.mailGroupings()
            val group = call.mailGroup(groupings)
            val filter = call.mailFilter()

            val userId = call.requireAuthenticatedUserId()

            val answer = call.database().query {
                val stretch = Emails
                    .leftJoin(ImapAccounts)
                    .select(Emails.id)
                    .where { (ImapAccounts.user eq userId) and filter.predicate() and group }

                // From the same query as the ids, so a client can tell an answer that was cut
                // from one that holds the whole group.
                val total = stretch.count()

                EmailIdsResponse(
                    total = total,
                    // Newest first, like the listing's own default; what a selection does with
                    // them does not depend on their order.
                    ids = stretch
                        .orderBy(Emails.sent to SortOrder.DESC, Emails.id to SortOrder.DESC)
                        .limit(MAX_IDS)
                        .map { row -> row[Emails.id].value },
                )
            }

            call.respond(answer)
        }
    }
}

@Serializable
private data class EmailIdsResponse(
    /** How many mails the stretch holds. More than [ids] when the answer was cut, see MAX_IDS. */
    @SerialName("total") val total: Long,
    @SerialName("ids") val ids: List<Uuid>,
)
