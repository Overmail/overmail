package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select

/**
 * As many ids as one answer here carries. A stretch of a mailbox is a day or a month of mail, so
 * this is far above what anybody has in one -- it is here so a request cannot be made to stream a
 * mailbox, not to page. [EmailIdsResponse.total] says when it bit.
 */
private const val MAX_IDS = 10_000

/**
 * Every mail of a stretch of the listing: `GET /api/emails/list/ids?from=<sec>&to=<sec>`.
 *
 * What `GET /api/emails/list` answers a screen at a time, this answers in one go for a range of
 * send times -- because that is what picking a whole stretch needs. A client that only has the
 * pages it scrolled through could tick those, and would quietly leave the rest of the day out of
 * whatever is done next with the selection.
 *
 * The range is `[from, to)` in whole seconds, the same clock the pages are cut by, and both ends
 * are optional: without them this is every mail of the scope. A day-grouped client passes the
 * midnight the stretch starts at and the midnight after its newest day, which are the same
 * boundaries `GET /api/emails/list/groups` counted its days by -- so the ids here and the count
 * of a header are about the same mails.
 *
 * Ids and a length, like the listing itself: what a row shows is subscribed per mail over the
 * content socket.
 *
 * Which mails are in it at all is `?scope=`, see [MailScope].
 */
fun Route.emailListIds() {
    authenticate {
        get {
            val scope = call.mailScope()
            val from = call.fromQueryParameter()
            val to = call.toQueryParameter()

            val userId = call.requireAuthenticatedUserId()

            val answer = call.database().query {
                val stretch = Emails
                    .leftJoin(ImapAccounts)
                    .select(Emails.id)
                    .where { (ImapAccounts.user eq userId) and scope.filter() }
                    .let { query -> if (from == null) query else query.andWhere { Emails.sent greaterEq from } }
                    .let { query -> if (to == null) query else query.andWhere { Emails.sent less to } }

                // From the same query as the ids, so a client can tell an answer that was cut
                // from one that holds the whole stretch.
                val total = stretch.count()

                EmailIdsResponse(
                    total = total,
                    // The listing's order, so the ids come newest first here as well.
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

/**
 * Where the stretch starts, in whole seconds since the epoch. Inclusive: it is a midnight, and
 * the mail sent at it is the first of that day.
 *
 * Read here rather than through the shared `instantQueryParameter`, and so is [toQueryParameter]:
 * the OpenAPI compiler plugin inlines what a handler calls and fails on the same helper reached
 * twice from one of them. Same reason the helpers in `http/api/QueryParameters.kt` each repeat
 * the read.
 */
private fun ApplicationCall.fromQueryParameter(): Instant? {
    val raw = request.queryParameters["from"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val seconds = raw.toLongOrNull()
        ?: invalidRequest("from", "is not a number of seconds since the epoch", raw)
    return Instant.fromEpochSeconds(seconds)
}

/** Where it ends, exclusive: the midnight after its newest day, which belongs to the next one. */
private fun ApplicationCall.toQueryParameter(): Instant? {
    val raw = request.queryParameters["to"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val seconds = raw.toLongOrNull()
        ?: invalidRequest("to", "is not a number of seconds since the epoch", raw)
    return Instant.fromEpochSeconds(seconds)
}

@Serializable
private data class EmailIdsResponse(
    /** How many mails the stretch holds. More than [ids] when the answer was cut, see MAX_IDS. */
    @SerialName("total") val total: Long,
    @SerialName("ids") val ids: List<Uuid>,
)
