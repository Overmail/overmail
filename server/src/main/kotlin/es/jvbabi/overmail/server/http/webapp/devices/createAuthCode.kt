package es.jvbabi.overmail.server.http.webapp.devices

import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.util.randomString
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class AuthCodeSession(
    val user: User,
    val validUntil: Instant,
    val code: Code,
) {
    typealias Code = String
}

val authCodeSessions = mutableMapOf<AuthCodeSession.Code, AuthCodeSession>()

/**
 * A code the app signs in with: `GET /api/webapp/devices/auth/generate-auth-code`.
 *
 * The web app shows it, the app sends it to `/api/auth/redeem`. Held in memory only, so a restart
 * drops every code that was not redeemed yet -- they only live five minutes anyway.
 */
fun Route.createAuthCode() {
    authenticate {
        /**
         * Create a sign-in code for the app.
         *
         * Description: Valid for five minutes and redeemed once, through `GET /api/auth/redeem`.
         *
         * Tag: Authentication
         *
         * Responses:
         *   - 200 [CreateAuthCodeResponse] The code
         */
        get {
            val user = call.principal<User>()!!

            val code = randomString(255)
            val validUntil = Clock.System.now() + 5.minutes

            authCodeSessions[code] = AuthCodeSession(user, validUntil, code)

            call.respond(CreateAuthCodeResponse(
                code = code,
                validUntil = validUntil.epochSeconds,
            ))
        }
    }
}

@Serializable
private data class CreateAuthCodeResponse(
    @SerialName("code") val code: String,
    @JsonSchema.Description("When the code runs out, in whole seconds since the epoch")
    @SerialName("valid_until") val validUntil: Long,
)