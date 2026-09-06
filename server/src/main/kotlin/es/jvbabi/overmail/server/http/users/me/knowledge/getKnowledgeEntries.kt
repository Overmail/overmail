package es.jvbabi.overmail.server.http.users.me.knowledge

import es.jvbabi.overmail.server.database.models.Knowledges
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Everything the assistant knows about this user: `GET /api/users/me/knowledge`.
 *
 * The whole list, not a search: this is the settings screen, where the point is to see what was
 * learned and correct it. The agent reads the same rows by keyword instead -- see `KnowledgeStore`.
 *
 * Freshest first, which is the order the entries were last learned or corrected in, and the order
 * a user looking for "what did it just pick up" reads in.
 */
fun Route.getKnowledgeEntries() {
    authenticate {
        get {
            val userId = call.requireAuthenticatedUserId()

            val entries = call.database().query {
                Knowledges
                    .selectAll()
                    .where { Knowledges.owner eq userId }
                    .orderBy(Knowledges.updatedAt to SortOrder.DESC)
                    .map { row -> row.toKnowledgeEntryPayload() }
            }

            call.respond(KnowledgeEntriesResponse(entries))
        }
    }
}

@Serializable
private data class KnowledgeEntriesResponse(
    @SerialName("knowledge") val knowledge: List<KnowledgeEntryPayload>,
)
