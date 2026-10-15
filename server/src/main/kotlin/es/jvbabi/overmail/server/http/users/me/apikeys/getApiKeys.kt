package es.jvbabi.overmail.server.http.users.me.apikeys

import es.jvbabi.overmail.server.database.models.ApiKeys
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
import org.jetbrains.exposed.v1.jdbc.select

/** The api keys of this user, newest first: `GET /api/users/me/api-keys`. */
fun Route.getApiKeys() {
    authenticate {
        /**
         * List the api keys of the current user.
         *
         * Description: Newest first. Only the start of every key is in here; the key itself is shown once, when it is made.
         *
         * Tag: ApiKeys
         *
         * Responses:
         *   - 200 [ApiKeysResponse] The keys
         */
        get {
            val userId = call.requireAuthenticatedUserId()

            val keys = call.database().query {
                ApiKeys
                    .select(ApiKeys.id, ApiKeys.name, ApiKeys.keyPrefix, ApiKeys.createdAt, ApiKeys.lastUsedAt)
                    .where { ApiKeys.user eq userId }
                    .orderBy(ApiKeys.createdAt to SortOrder.DESC)
                    .map { it.toApiKeyPayload() }
            }

            call.respond(ApiKeysResponse(keys))
        }
    }
}

@Serializable
private data class ApiKeysResponse(
    @SerialName("api_keys") val apiKeys: List<ApiKeyPayload>,
)
