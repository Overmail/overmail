package es.jvbabi.overmail.server.http.users.me.apikeys

import es.jvbabi.overmail.server.auth.generateApiKey
import es.jvbabi.overmail.server.database.models.ApiKeys
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select

/** The longest name a key may have, as [ApiKeys.name] stores it. */
private const val MAX_NAME_LENGTH = 100

/**
 * Makes an api key: `POST /api/users/me/api-keys`.
 *
 * The answer is the one place the key ever appears -- only its hash is stored, so a key that was
 * not copied from here is a key to delete and make again.
 */
fun Route.createApiKey() {
    authenticate {
        /**
         * Create an api key.
         *
         * Description: The key is in this answer and nowhere else, ever. It signs in as the current user through `Authorization: Bearer`.
         *
         * Tag: ApiKeys
         *
         * Body: [CreateApiKeyRequest] The key
         *
         * Responses:
         *   - 201 [CreatedApiKeyResponse] The new key, with the key itself
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A blank or too long name
         */
        post {
            val userId = call.requireAuthenticatedUserId()
            val request = call.receive<CreateApiKeyRequest>()

            val name = request.name.trim()
            if (name.isEmpty()) invalidRequest("name", "is blank")
            if (name.length > MAX_NAME_LENGTH) invalidRequest("name", "is longer than $MAX_NAME_LENGTH characters")

            val generated = generateApiKey()
            val payload = call.database().query {
                val id = ApiKeys.insertAndGetId {
                    it[user] = userId
                    it[ApiKeys.name] = name
                    it[keyHash] = generated.hash
                    it[keyPrefix] = generated.prefix
                }

                ApiKeys
                    .select(ApiKeys.id, ApiKeys.name, ApiKeys.keyPrefix, ApiKeys.createdAt, ApiKeys.lastUsedAt)
                    .where { ApiKeys.id eq id }
                    .single()
                    .toApiKeyPayload()
            }

            call.respond(HttpStatusCode.Created, CreatedApiKeyResponse(apiKey = payload, key = generated.key))
        }
    }
}

@Serializable
private data class CreateApiKeyRequest(
    @JsonSchema.Description("What the key is for, at most 100 characters")
    @SerialName("name") val name: String,
)

@Serializable
private data class CreatedApiKeyResponse(
    @SerialName("api_key") val apiKey: ApiKeyPayload,
    @JsonSchema.Description("The key itself. Shown this once: only its hash is stored")
    @SerialName("key") val key: String,
)
