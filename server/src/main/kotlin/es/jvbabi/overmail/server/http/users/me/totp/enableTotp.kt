package es.jvbabi.overmail.server.http.users.me.totp

import es.jvbabi.overmail.server.auth.TOTP_SECRET_PATTERN
import es.jvbabi.overmail.server.auth.verifyTotp
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.http.api.conflict
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Turns the authenticator app on: `PUT /api/users/me/totp`, with the secret from `setup` and the
 * code the app shows for it -- the proof that it was scanned.
 *
 * One that is set up already is a 409 rather than replaced: swapping it would take a code from the
 * old app, and that is turning it off and setting it up again.
 */
fun Route.enableTotp() {
    authenticate {
        /**
         * Confirm an authenticator app.
         *
         * Description: Takes the secret from the setup and a code the app shows for it.
         *
         * Tag: Account
         *
         * Body: [EnableTotpRequest] The secret and a code
         *
         * Responses:
         *   - 204 The app is set up
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A secret that is not one, or a code that does not match it
         *   - 409 [es.jvbabi.overmail.server.http.api.ApiErrorBody] An app is set up already
         */
        put {
            val userId = call.requireAuthenticatedUserId()
            val request = call.receive<EnableTotpRequest>()

            if (!TOTP_SECRET_PATTERN.matches(request.secret)) invalidRequest("secret", "not a base32 secret")
            if (!verifyTotp(request.secret, request.code)) invalidRequest("code", "does not match the secret")

            val updated = call.database().query {
                // Only where there is none yet, so two confirmations at once cannot both win.
                Users.update({ (Users.id eq userId) and Users.totpSecret.isNull() }) {
                    it[totpSecret] = request.secret
                }
            }
            if (updated == 0) conflict("An authenticator app is set up already")

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class EnableTotpRequest(
    @SerialName("secret") val secret: String,
    @SerialName("code") val code: String,
)
