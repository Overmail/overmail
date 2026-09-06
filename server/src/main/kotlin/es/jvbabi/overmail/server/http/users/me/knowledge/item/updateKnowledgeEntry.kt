package es.jvbabi.overmail.server.http.users.me.knowledge.item

import es.jvbabi.overmail.server.database.models.Knowledges
import es.jvbabi.overmail.server.http.api.conflict
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.users.me.knowledge.readKnowledgeInput
import es.jvbabi.overmail.server.http.users.me.knowledge.toKnowledgeEntryPayload
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlin.time.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Corrects an entry: `PUT /api/users/me/knowledge/{knowledgeId}`.
 *
 * The whole entry is sent, not a change to it: the screen holds what it is showing and sends that
 * back, so leaving a keyword out is how one is removed. Same contract as the agent's writes.
 *
 * Addressed by id rather than by name, unlike `KnowledgeStore.write`, because renaming is one of
 * the things this screen is for -- writing the new name would leave the old entry standing beside
 * it. `created_by_agent` stays as it is: it says who wrote the entry, and correcting one does not
 * make it the user's.
 *
 * An entry that is not this user's is a 404, not a 403: answering otherwise would confirm the id
 * belongs to somebody.
 */
fun Route.updateKnowledgeEntry() {
    authenticate {
        put {
            val userId = call.requireAuthenticatedUserId()
            val knowledgeId = knowledgeIdFromPath(call.parameters["knowledgeId"])
            val request = call.receive<UpdateKnowledgeRequest>()

            val input = readKnowledgeInput(
                name = request.name,
                description = request.description,
                keywords = request.keywords,
                relevantOn = request.relevantOn,
            )
            val now = Clock.System.now()

            val entry = call.database().query {
                val owned = Knowledges
                    .selectAll()
                    .where { (Knowledges.id eq knowledgeId) and (Knowledges.owner eq userId) }
                    .count() > 0L
                if (!owned) notFound("knowledge", knowledgeId.toString())

                // The unique index would answer this with a 500; the screen wants to know it was
                // the name. Case-insensitive, like the agent's lookup.
                val taken = Knowledges
                    .selectAll()
                    .where {
                        (Knowledges.owner eq userId) and
                            (Knowledges.id neq knowledgeId) and
                            (Knowledges.name.lowerCase() eq input.name.lowercase())
                    }
                    .count() > 0L
                if (taken) conflict("There is already an entry of that name", mapOf("name" to input.name))

                Knowledges.update({ Knowledges.id eq knowledgeId }) {
                    it[name] = input.name
                    it[description] = input.description
                    it[keywords] = input.keywords
                    it[relevantOn] = input.relevantOn
                    // What the search puts first among equally good hits, so a corrected entry
                    // reads as the freshest one -- which it is.
                    it[updatedAt] = now
                }

                Knowledges.selectAll().where { Knowledges.id eq knowledgeId }.single().toKnowledgeEntryPayload()
            }

            call.respond(HttpStatusCode.OK, entry)
        }
    }
}

@Serializable
private data class UpdateKnowledgeRequest(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    /** The complete list the screen is showing; anything left out is a keyword the user removed. */
    @SerialName("keywords") val keywords: List<String> = emptyList(),
    /** `YYYY-MM-DD`, or null to say this entry is not about a day. */
    @SerialName("relevant_on") val relevantOn: String? = null,
)
