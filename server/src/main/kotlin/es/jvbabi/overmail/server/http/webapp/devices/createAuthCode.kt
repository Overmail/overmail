package es.jvbabi.overmail.server.http.webapp.devices

import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.util.randomString
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

fun Route.createAuthCode() {
    authenticate {
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
    @SerialName("valid_until") val validUntil: Long,
)