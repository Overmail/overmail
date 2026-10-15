package es.jvbabi.overmail.server.http.users.me.apikeys.item

import es.jvbabi.overmail.server.database.models.ApiKeys
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireOwnedApiKeyFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere

/**
 * Takes an api key away: `DELETE /api/users/me/api-keys/{apiKeyId}`.
 *
 * Deleted rather than marked revoked like a session: nothing refers to a key, and a key that no
 * longer works has nothing left to show.
 */
fun Route.deleteApiKey() {
    authenticate {
        /**
         * Delete an api key.
         *
         * Description: It is refused from the next request on.
         *
         * Tag: ApiKeys
         *
         * Responses:
         *   - 204 The key is deleted
         */
        delete {
            val apiKey = call.requireOwnedApiKeyFromUrl()

            call.database().query {
                ApiKeys.deleteWhere { ApiKeys.id eq apiKey.id }
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
