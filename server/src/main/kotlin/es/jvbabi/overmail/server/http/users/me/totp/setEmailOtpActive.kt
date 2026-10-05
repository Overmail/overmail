package es.jvbabi.overmail.server.http.users.me.totp

import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Whether a mailed code may stand in for the authenticator app at sign-in:
 * `PUT /api/users/me/totp/email-otp`.
 *
 * Kept while no app is set up, so the choice is still there when one is. Either way takes no code:
 * turning it off only asks more of a sign-in, and turning it on still takes the mailbox.
 */
fun Route.setEmailOtpActive() {
    authenticate {
        /**
         * Allow or forbid the mailed code instead of the authenticator app.
         *
         * Description: Kept without an app as well, and applies once one is set up.
         *
         * Tag: Account
         *
         * Body: [EmailOtpActiveRequest] Whether it is allowed
         *
         * Responses:
         *   - 204 The setting is saved
         */
        put {
            val userId = call.requireAuthenticatedUserId()
            val request = call.receive<EmailOtpActiveRequest>()

            call.database().query {
                Users.update({ Users.id eq userId }) { it[emailOtpActive] = request.enabled }
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class EmailOtpActiveRequest(
    @SerialName("enabled") val enabled: Boolean,
)
