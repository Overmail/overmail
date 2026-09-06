package es.jvbabi.overmail.server.http.users.me.knowledge

import es.jvbabi.overmail.server.database.models.Knowledges
import es.jvbabi.overmail.server.http.api.conflict
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Writes something the user wants the assistant to know: `POST /api/users/me/knowledge`.
 *
 * `created_by_agent` is false here and nowhere else -- an entry made on this route is one the user
 * typed, and the screen says so next to the ones that were learned.
 *
 * A name that is already taken is a 409 rather than an overwrite. The agent's own writes upsert on
 * the name (see `KnowledgeStore.write`), because a model that learns more about "Stromvertrag"
 * means that entry; a user filling in a form means a new one, and silently replacing an entry they
 * cannot see would lose what it said.
 */
fun Route.createKnowledgeEntry() {
    authenticate {
        post {
            val userId = call.requireAuthenticatedUserId()
            val request = call.receive<CreateKnowledgeRequest>()

            val input = readKnowledgeInput(
                name = request.name,
                description = request.description,
                keywords = request.keywords,
                relevantOn = request.relevantOn,
            )

            val entry = call.database().query {
                // Case-insensitively, like the lookup the agent writes through: two entries whose
                // names differ in capitals are one entry to everything that reads them.
                val taken = Knowledges
                    .selectAll()
                    .where {
                        (Knowledges.owner eq userId) and (Knowledges.name.lowerCase() eq input.name.lowercase())
                    }
                    .count() > 0L
                if (taken) conflict("There is already an entry of that name", mapOf("name" to input.name))

                val id = Knowledges.insertAndGetId {
                    it[owner] = userId
                    it[name] = input.name
                    it[description] = input.description
                    it[keywords] = input.keywords
                    it[relevantOn] = input.relevantOn
                    it[createdByAgent] = false
                }

                Knowledges.selectAll().where { Knowledges.id eq id }.single().toKnowledgeEntryPayload()
            }

            call.respond(HttpStatusCode.Created, entry)
        }
    }
}

@Serializable
private data class CreateKnowledgeRequest(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("keywords") val keywords: List<String> = emptyList(),
    /** `YYYY-MM-DD`, or null for the entries that are not about a day -- which is most of them. */
    @SerialName("relevant_on") val relevantOn: String? = null,
)
