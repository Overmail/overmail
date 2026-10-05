package es.jvbabi.overmail.server.http.users.me.password

import es.jvbabi.overmail.server.auth.PASSWORD_MAX_BYTES
import es.jvbabi.overmail.server.auth.PASSWORD_MIN_LENGTH
import es.jvbabi.overmail.server.auth.hashPassword
import es.jvbabi.overmail.server.auth.verifyPassword
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Sets or changes the password: `PUT /api/users/me/password`.
 *
 * Changing one takes the current one. A session alone is not enough there: whoever holds a stolen
 * cookie could otherwise lock the owner out of their own password. Setting the first one does not
 * -- the account had only its mailbox to sign in with, and the session proves that already.
 */
fun Route.setPassword() {
    authenticate {
        /**
         * Set or change the password.
         *
         * Description: Changing an existing password takes the current one.
         *
         * Tag: Account
         *
         * Body: [SetPasswordRequest] The new password
         *
         * Responses:
         *   - 204 The password is set
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A new password that is too short or too long, or a `current_password` that is missing or wrong
         */
        put {
            val userId = call.requireAuthenticatedUserId()
            val request = call.receive<SetPasswordRequest>()

            if (request.newPassword.length < PASSWORD_MIN_LENGTH) {
                invalidRequest("new_password", "shorter than $PASSWORD_MIN_LENGTH characters")
            }
            if (request.newPassword.toByteArray().size > PASSWORD_MAX_BYTES) {
                invalidRequest("new_password", "longer than $PASSWORD_MAX_BYTES bytes")
            }

            val currentHash = call.database().query {
                Users.select(Users.password).where { Users.id eq userId }.single()[Users.password]
            }
            if (currentHash != null) {
                val current = request.currentPassword ?: invalidRequest("current_password", "required to change the password")
                if (!verifyPassword(current, currentHash)) invalidRequest("current_password", "does not match")
            }

            val hash = hashPassword(request.newPassword)
            call.database().query {
                Users.update({ Users.id eq userId }) { it[password] = hash }
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class SetPasswordRequest(
    @JsonSchema.Description("Required when a password is set already")
    @SerialName("current_password") val currentPassword: String? = null,
    @JsonSchema.Description("At least 8 characters, at most 72 bytes")
    @SerialName("new_password") val newPassword: String,
)
