package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/** What one request may ask for. A windowed table asks for a screen and some overscan. */
private const val MAX_LIMIT = 500
private const val DEFAULT_LIMIT = 100

/**
 * Which mails the mailbox holds, newest first: `GET /api/emails/list?offset=0&limit=100`.
 *
 * Ids and a length, nothing else. What a row shows is subscribed per mail over the content
 * socket, so this answer stays the same size whether a row carries a subject or a whole thread --
 * and a listing that scrolls asks for the slice it needs rather than for mails it will not draw.
 *
 * Offset paging, so an index in a virtualized list is a request: `offset` is that index. What the
 * list holds is `listFilter`: spam never, archived mails on request.
 */
fun Route.emailList() {
    authenticate {
        get {
            val offset = call.request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
                .coerceIn(1, MAX_LIMIT)

            val database = call.application.dependencies.resolve<OvermailDatabase>()
            val userId = call.user.id.value
            val includeArchived = call.listIncludesArchived()

            val answer = database.query {
                val mailbox = Emails
                    .leftJoin(ImapAccounts)
                    .select(Emails.id)
                    .where { (ImapAccounts.user eq userId) and listFilter(includeArchived) }

                // The count comes from the same query as the page, so the length a client sizes
                // its scrollbar from and the ids it draws cannot disagree.
                val total = mailbox.count()

                val ids = mailbox
                    .orderBy(Emails.sent, SortOrder.DESC)
                    .limit(limit)
                    .offset(offset)
                    .map { row -> row[Emails.id].value }

                EmailListResponse(total = total, offset = offset, ids = ids)
            }

            call.respond(answer)
        }
    }
}

@Serializable
private data class EmailListResponse(
    /** How long the list is, not how much of it was asked for. */
    @SerialName("total") val total: Long,
    /** Where [ids] start, echoed so an answer that overtook another can be placed. */
    @SerialName("offset") val offset: Long,
    @SerialName("ids") val ids: List<Uuid>,
)
