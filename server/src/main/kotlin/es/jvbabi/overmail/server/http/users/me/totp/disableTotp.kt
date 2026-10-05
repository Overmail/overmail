package es.jvbabi.overmail.server.http.users.me.totp

import es.jvbabi.overmail.server.auth.verifyTotp
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
 * Turns the authenticator app off: `DELETE /api/users/me/totp`, with a code from it. A session
 * alone is not enough, or a stolen cookie could take the second factor away.
 */
fun Route.disableTotp() {
    authenticate {
        /**
         * Remove the authenticator app.
         *
         * Description: Takes a current code from it. Idempotent, an account without one answers 204 as well.
         *
         * Tag: Account
         *
         * Body: [DisableTotpRequest] A current code
         *
         * Responses:
         *   - 204 There is no authenticator app any more
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A code that does not match
         */
        delete {
            val userId = call.requireAuthenticatedUserId()
            val request = call.receive<DisableTotpRequest>()

            val secret = call.database().query {
                Users.select(Users.totpSecret).where { Users.id eq userId }.single()[Users.totpSecret]
            }
            if (secret != null) {
                if (!verifyTotp(secret, request.code)) invalidRequest("code", "does not match")
                call.database().query {
                    Users.update({ Users.id eq userId }) { it[totpSecret] = null }
                }
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class DisableTotpRequest(
    @SerialName("code") val code: String,
)
