package es.jvbabi.overmail.server.http.users.me.knowledge.item

import es.jvbabi.overmail.server.database.models.Knowledges
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere

/**
 * Forgets an entry: `DELETE /api/users/me/knowledge/{knowledgeId}`.
 *
 * Nothing hangs off a knowledge row, so this takes the row and nothing else. What was already
 * written into a mail's classification with its help stays -- the entry is what the assistant
 * reads next time, not a record of what it did.
 *
 * The delete is scoped to the owner as well as the id, so there is no moment where a foreign row
 * was matched; a miss on either is the same 404.
 */
fun Route.deleteKnowledgeEntry() {
    authenticate {
        delete {
            val userId = call.requireAuthenticatedUserId()
            val knowledgeId = knowledgeIdFromPath(call.parameters["knowledgeId"])

            val deleted = call.database().query {
                Knowledges.deleteWhere { (Knowledges.id eq knowledgeId) and (Knowledges.owner eq userId) }
            }
            if (deleted == 0) notFound("knowledge", knowledgeId.toString())

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
