package es.jvbabi.overmail.server.http.users.me.totp

import es.jvbabi.overmail.server.auth.newTotpSecret
import es.jvbabi.overmail.server.auth.totpUri
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A secret to scan: `POST /api/users/me/totp/setup`.
 *
 * Nothing is stored. The secret only counts once the app proves it has it, by the code sent with
 * `PUT /api/users/me/totp` -- a scan that never happened must not lock anybody out of their account.
 */
fun Route.setupTotp() {
    authenticate {
        /**
         * Start setting up an authenticator app.
         *
         * Description: Answers a fresh secret and the URI to put in a QR code. Nothing is stored until it is confirmed.
         *
         * Tag: Account
         *
         * Responses:
         *   - 200 [TotpSetupResponse] The secret
         */
        post {
            val user = call.requireAuthenticatedUser()
            val secret = newTotpSecret()
            call.respond(TotpSetupResponse(secret = secret, uri = totpUri(secret, user.email)))
        }
    }
}

@Serializable
data class TotpSetupResponse(
    @JsonSchema.Description("Base32, for typing into an app that cannot scan")
    @SerialName("secret") val secret: String,
    @JsonSchema.Description("An otpauth URI with the secret, for the QR code")
    @SerialName("uri") val uri: String,
)
