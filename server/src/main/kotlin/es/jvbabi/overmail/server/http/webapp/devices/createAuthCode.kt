package es.jvbabi.overmail.server.http.webapp.devices

import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.util.randomString
import io.ktor.http.encodeURLParameter
import io.ktor.server.auth.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How long a code stays redeemable. Short: it is a bearer credential shown on a screen. */
private val CODE_VALIDITY = 5.minutes

data class AuthCodeSession(
    val user: User,
    val validUntil: Instant,
    val code: Code,
) {
    typealias Code = String
}

/**
 * The codes handed out here, until they are redeemed or run out. Concurrent because the browser
 * that creates one and the app that redeems it are two requests, see `http/auth/instantAuth.kt`.
 *
 * In memory on purpose: a restart invalidating every unredeemed code is correct, and five minutes
 * of them is nothing to keep.
 */
val authCodeSessions: MutableMap<AuthCodeSession.Code, AuthCodeSession> = ConcurrentHashMap()

fun Route.createAuthCode() {
    authenticate {
        get {
            val config = application.dependencies.resolve<ApplicationConfig>()

            val user = call.principal<User>()!!

            val now = Clock.System.now()
            val code = randomString(255)
            val validUntil = now + CODE_VALIDITY

            // Nothing else ever removes a code that was never scanned, so this does.
            authCodeSessions.values.removeAll { it.validUntil <= now }
            authCodeSessions[code] = AuthCodeSession(user, validUntil, code)

            // `overmail://<encoded server origin>/auth?code=<code>`, which is what
            // `LoginCode.parse` in the app reads. The origin travels with the code so a scan
            // reaches this server and not whichever one the app was built against, and it is
            // encoded because its own `://` would otherwise leave two urls in one string.
            val origin = config.baseUrl.trimEnd('/').encodeURLParameter()

            call.respond(CreateAuthCodeResponse(
                code = "overmail://$origin/auth?code=${code.encodeURLParameter()}",
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