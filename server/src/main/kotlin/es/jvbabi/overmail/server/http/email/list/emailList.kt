package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.intQueryParameter
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

/** What one request may ask for. A windowed table asks for a screen and some overscan. */
private const val MAX_LIMIT = 500
private const val DEFAULT_LIMIT = 100

/**
 * The mails of one group of the listing: `GET /api/emails/list?by=date_smart&group=2026-09-14`.
 *
 * Ids and a length, nothing else. What a row shows is subscribed per mail over the content
 * socket, so this answer stays the same size whether a row carries a subject or a whole thread --
 * and a listing that scrolls asks for the slice it needs rather than for mails it will not draw.
 *
 * A group and an offset into it, rather than a cursor through the whole listing. The groups are
 * the shape a client laid its rows out from (see `emailListGroups`), so a table that scrolls into
 * the middle asks for exactly the rows it is missing instead of paging its way down to them --
 * and an order that is not the send time has no cursor to page by in the first place. What it
 * costs is the offset, which the database walks; a group is a day or a correspondent of mail, and
 * the ungrouped listing is the one case where that walk is the whole mailbox.
 *
 * `by` says what the keys in `group` mean and has to be the list the groups were read with;
 * without either, this is the whole listing as one group. Which mails are in it at all is the
 * filter, see [MailFilter]; what orders them inside the group is `sort`, see [MailSorting].
 */
fun Route.emailList() {
    authenticate {
        get {
            val groupings = call.mailGroupings()
            val group = call.mailGroup(groupings)
            val filter = call.mailFilter()
            val sorting = call.mailSorting()
            val limit = call.intQueryParameter("limit", default = DEFAULT_LIMIT, range = 1..MAX_LIMIT)
            val offset = call.intQueryParameter("offset", default = 0, range = 0..1_000_000)

            val userId = call.requireAuthenticatedUserId()

            val answer = call.database().query {
                // The sender's address is joined in only for the sort that reads it; every other
                // one is the mails alone.
                val source = if (sorting.first.needsSender()) {
                    Emails.leftJoin(ImapAccounts).innerJoin(EmailUsers)
                } else {
                    Emails.leftJoin(ImapAccounts)
                }

                val mails = source
                    .select(Emails.id)
                    .where { (ImapAccounts.user eq userId) and filter.predicate() and group }

                // The count comes from the same query as the page, so the length a client checks
                // its layout against and the ids it draws cannot disagree.
                val total = mails.count()

                val natural = sorting.first.naturalOrder()
                val order = if (!sorting.second) natural
                else if (natural == SortOrder.DESC) SortOrder.ASC
                else SortOrder.DESC

                val page = mails
                    .orderBy(sorting.first.orderBy() to order, Emails.id to order)
                    .limit(limit)
                    .offset(offset.toLong())
                    .map { row -> row[Emails.id].value }

                EmailListResponse(total = total, ids = page)
            }

            call.respond(answer)
        }
    }
}

@Serializable
private data class EmailListResponse(
    /** How long the group is, not how much of it was asked for. */
    @SerialName("total") val total: Long,
    @SerialName("ids") val ids: List<Uuid>,
)
