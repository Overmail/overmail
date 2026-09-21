package es.jvbabi.overmail.server.http.auth.redeem

import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.auth.SESSION_VALIDITY
import es.jvbabi.overmail.server.http.api.ApiErrorCode
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.queryParameter
import es.jvbabi.overmail.server.http.webapp.devices.authCodeSessions
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Trades a device sign-in code from the web app for a session token. A code works once, whether
 * it is still valid or not.
 */
fun Route.redeemAuthCode() {
    get {
        val code = call.queryParameter("code") ?: invalidRequest("code", "is required")

        // Removed while it is looked up, so the same code cannot be redeemed twice.
        // The code is a credential, so it stays out of the error details.
        val session = authCodeSessions.remove(code) ?: notFound("auth code")

        if (session.validUntil <= Clock.System.now()) {
            throw ApiException(
                status = HttpStatusCode.Gone,
                code = ApiErrorCode.GONE,
                message = "This auth code has run out",
                details = mapOf("resource" to "auth code"),
            )
        }

        val jwtService = call.dependency<JwtService>()
        call.respond(RedeemResponse(jwt = jwtService.issue(session.user.id.value, SESSION_VALIDITY)))
    }
}

@Serializable
data class RedeemResponse(
    @SerialName("jwt") val jwt: String,
)
