package es.jvbabi.overmail.server.http.auth.redeem

import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.auth.issueSession
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.http.api.ApiErrorCode
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.queryParameter
import es.jvbabi.overmail.server.http.webapp.devices.authCodeSessions
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Trades a device sign-in code from the web app for a session token. A code works once, whether
 * it is still valid or not. `platform`, `device`, `manufacturer` and `os` describe the app's device
 * and are recorded with the session.
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

        val jwt = call.dependency<JwtService>().issueSession(
            database = call.database(),
            userId = session.user.id.value,
            client = appClientOf(call.request.queryParameters),
        )
        call.respond(RedeemResponse(jwt = jwt))
    }
}

/**
 * The device the app describes along with the code. An app from before it did sends nothing, and
 * is recorded as an Android device nobody knows anything about.
 */
private fun appClientOf(parameters: Parameters): Session.Client {
    fun read(name: String) = parameters[name]?.trim()?.takeIf { it.isNotEmpty() } ?: Session.Client.UNKNOWN

    return when (parameters["platform"]) {
        "ios" -> Session.Client.Ios(device = read("device"), os = read("os"))
        else -> Session.Client.Android(device = read("device"), manufacturer = read("manufacturer"), os = read("os"))
    }
}

@Serializable
data class RedeemResponse(
    @SerialName("jwt") val jwt: String,
)
