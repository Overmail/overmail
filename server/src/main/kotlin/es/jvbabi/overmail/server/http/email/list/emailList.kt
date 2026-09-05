package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.instantQueryParameter
import es.jvbabi.overmail.server.http.api.intQueryParameter
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.api.uuidQueryParameter
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
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select

/** What one request may ask for. A windowed table asks for a screen and some overscan. */
private const val MAX_LIMIT = 500
private const val DEFAULT_LIMIT = 100

/**
 * Which mails the mailbox holds, newest first: `GET /api/emails/list?limit=100`.
 *
 * Ids and a length, nothing else. What a row shows is subscribed per mail over the content
 * socket, so this answer stays the same size whether a row carries a subject or a whole thread --
 * and a listing that scrolls asks for the slice it needs rather than for mails it will not draw.
 *
 * Paged by send time, not by offset: `before` is a send time, and what comes back is the mails
 * older than it. Two reasons. The database walks the index instead of counting the rows in front
 * of the page, which an offset makes it do -- and a position is only a position within one scope,
 * so a client that switches scope would have to throw away everything it holds, while a send time
 * still means the same mail.
 *
 * The cursor is a whole second because [Emails.sent] is: it is stored truncated, as the dedup key
 * of the importer. `sent` alone is not unique though, so `before_id` continues within a second,
 * and the order has the id as its tiebreaker -- without it two pages could repeat or skip a mail
 * that shares its second with another. Days are cut in the server's zone, see `emailListGroups`,
 * so a day boundary passed as `before` lands exactly between two stretches.
 *
 * Which mails are in it at all is `?scope=`, see [MailScope].
 */
fun Route.emailList() {
    authenticate {
        get {
            val scope = call.mailScope()
            val limit = call.intQueryParameter("limit", default = DEFAULT_LIMIT, range = 1..MAX_LIMIT)
            val before = call.instantQueryParameter("before")
            val beforeId = call.uuidQueryParameter("before_id")

            val userId = call.requireAuthenticatedUserId()

            val answer = call.database().query {
                val mailbox = Emails
                    .leftJoin(ImapAccounts)
                    .select(Emails.id, Emails.sent)
                    .where { (ImapAccounts.user eq userId) and scope.filter() }

                // The count comes from the same query as the page, so the length a client sizes
                // its scrollbar from and the ids it draws cannot disagree.
                val total = mailbox.count()

                val page = mailbox
                    .let { query ->
                        if (before == null) query
                        else if (beforeId == null) query.andWhere { Emails.sent less before }
                        else query.andWhere {
                            (Emails.sent less before) or
                                    ((Emails.sent eq before) and (Emails.id less beforeId))
                        }
                    }
                    .orderBy(Emails.sent to SortOrder.DESC, Emails.id to SortOrder.DESC)
                    .limit(limit)
                    .map { row -> row[Emails.id].value to row[Emails.sent] }

                EmailListResponse(
                    total = total,
                    ids = page.map { (id, _) -> id },
                    // Where the next page carries on, or null at the end of the mailbox.
                    next = page.lastOrNull()
                        ?.takeIf { page.size == limit }
                        ?.let { (id, sent) -> EmailListResponse.Cursor(sent.epochSeconds, id) },
                )
            }

            call.respond(answer)
        }
    }
}

@Serializable
private data class EmailListResponse(
    /** How long the list is in this scope, not how much of it was asked for. */
    @SerialName("total") val total: Long,
    @SerialName("ids") val ids: List<Uuid>,
    @SerialName("next") val next: Cursor?,
) {
    /** What to pass as `before` and `before_id` for the page after this one. */
    @Serializable
    data class Cursor(
        @SerialName("before") val before: Long,
        @SerialName("before_id") val beforeId: Uuid,
    )
}
