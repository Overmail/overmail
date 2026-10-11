package es.jvbabi.overmail.server.http.users.me.inboxes.item

import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Renames a mailbox: `PUT /api/users/me/inboxes/{inboxId}/name`.
 *
 * A route of its own rather than the save next door: that one takes the whole inbox, looks into
 * the mailbox for it and restarts the importer -- none of which a name has anything to do with.
 * This writes one column and leaves the importer alone.
 */
fun Route.setInboxName() {
    authenticate {
        /**
         * Rename an inbox.
         *
         * Description: Only the name changes; the connection, the folders and the importer are left alone.
         *
         * Tag: Inboxes
         *
         * Body: [InboxNameRequest] The name
         *
         * Responses:
         *   - 200 [InboxNameResponse] The name as it is stored
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A name that is too long
         */
        put {
            val userId = call.requireAuthenticatedUserId()
            val inboxId = inboxIdFromPath(call.parameters["inboxId"])
            val name = inboxName(call.receive<InboxNameRequest>().name)

            // Owner and write in one statement: a mailbox that is not this user's matches nothing.
            val renamed = call.database().query {
                ImapAccounts.update({ (ImapAccounts.id eq inboxId) and (ImapAccounts.user eq userId) }) {
                    it[ImapAccounts.name] = name
                }
            }
            if (renamed == 0) notFound("inbox", inboxId.toString())

            call.respond(HttpStatusCode.OK, InboxNameResponse(name = name))
        }
    }
}

@Serializable
private data class InboxNameRequest(
    @JsonSchema.Description("What the user calls it; null or blank leaves it without a name")
    @SerialName("name") val name: String? = null,
)

@Serializable
private data class InboxNameResponse(
    @JsonSchema.Description("Trimmed; null for an inbox without a name")
    @SerialName("name") val name: String?,
)
