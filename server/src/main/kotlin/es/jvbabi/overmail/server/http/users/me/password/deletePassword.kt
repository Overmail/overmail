package es.jvbabi.overmail.server.http.users.me.password

import es.jvbabi.overmail.server.auth.verifyPassword
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Removes the password: `DELETE /api/users/me/password`. The account signs in with a mailed code
 * again. Takes the current password, for the same reason changing it does (see `setPassword`).
 */
fun Route.deletePassword() {
    authenticate {
        /**
         * Remove the password.
         *
         * Description: The account signs in with a mailed code again. Idempotent, an account without a password answers 204 as well.
         *
         * Tag: Account
         *
         * Body: [DeletePasswordRequest] The current password
         *
         * Responses:
         *   - 204 There is no password any more
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A `current_password` that does not match
         */
        delete {
            val userId = call.requireAuthenticatedUserId()
            val request = call.receive<DeletePasswordRequest>()

            val currentHash = call.database().query {
                Users.select(Users.password).where { Users.id eq userId }.single()[Users.password]
            }
            if (currentHash != null) {
                if (!verifyPassword(request.currentPassword, currentHash)) invalidRequest("current_password", "does not match")
                call.database().query {
                    Users.update({ Users.id eq userId }) { it[password] = null }
                }
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class DeletePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
)
