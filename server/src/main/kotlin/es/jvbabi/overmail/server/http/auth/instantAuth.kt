package es.jvbabi.overmail.server.http.auth

import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.http.api.ApiErrorCode
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.webapp.devices.authCodeSessions
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** How long the token handed out here lasts. The same as a browser session, see `InstallAuthentikt`. */
private val TOKEN_VALIDITY = 30.days

/**
 * Trades a one-time code from the web app's device settings for a session token:
 * `GET /api/auth/instant-auth?code=<code>`.
 *
 * No session of its own -- holding the code *is* the authentication, which is why `createAuthCode`
 * only ever hands one to an already signed-in browser and gives it five minutes. The code is
 * removed before anything is issued, so a second caller with the same code gets a 404 even if the
 * two arrive together.
 *
 * The token is the same JWT the browser cookie carries; the app sends it as `Authorization: Bearer`,
 * see `auth/CurrentUser.kt`.
 */
fun Route.instantAuth() {
    get {
        val code = call.request.queryParameters["code"]
        if (code.isNullOrBlank()) {
            throw ApiException(
                status = HttpStatusCode.BadRequest,
                code = ApiErrorCode.INVALID_REQUEST,
                message = "This request needs a code",
                details = mapOf("parameter" to "code"),
            )
        }

        val session = authCodeSessions.remove(code)
        if (session == null) {
            throw ApiException(
                status = HttpStatusCode.NotFound,
                code = ApiErrorCode.NOT_FOUND,
                message = "No such auth code",
                details = mapOf("resource" to "auth_code"),
            )
        }

        // Expiry is checked after the removal, so a code that ran out is gone either way.
        if (session.validUntil <= Clock.System.now()) {
            throw ApiException(
                status = HttpStatusCode.Gone,
                code = ApiErrorCode.GONE,
                message = "This auth code has expired",
                details = mapOf("resource" to "auth_code"),
            )
        }

        val token = call.application.dependencies.resolve<JwtService>().issue(session.user.id.value, TOKEN_VALIDITY)

        call.respond(
            InstantAuthResponse(
                token = token,
                userId = session.user.id.value,
                username = session.user.username,
                email = session.user.email,
            )
        )
    }
}

@Serializable
private data class InstantAuthResponse(
    /** The session token, to be sent as `Authorization: Bearer <token>` from here on. */
    @SerialName("token") val token: String,
    @SerialName("user_id") val userId: Uuid,
    @SerialName("username") val username: String,
    @SerialName("email") val email: String,
)
