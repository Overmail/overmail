package es.jvbabi.overmail.server.auth

import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.http.auth.parseAuthorizationHeader as parseAuthorizationHeaderValue
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.response.respond
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/** Name of the authentication the session routes run under. */
const val SESSION_AUTH = "overmail-session"

/**
 * Validates the token [JwtService] issues, so a route can ask for a signed-in user.
 *
 * The token is read from the session cookie as well as from an `Authorization: Bearer` header: the
 * browser gets it set as a cookie it cannot read, while anything talking to the API directly --
 * Swagger, curl, a future app -- carries it in the header.
 *
 * A token that verifies is not enough on its own: the user it names is looked up, so a session for
 * an account that has since been deleted stops working.
 */
fun Application.installSessionAuth() {
    val jwtService: JwtService by dependencies

    install(Authentication) {
        jwt(SESSION_AUTH) {
            authHeader { call ->
                call.request.parseAuthorizationHeader()
                    ?: call.request.cookies[SESSION_COOKIE_NAME]?.let {
                        runCatching { parseAuthorizationHeaderValue("Bearer $it") }.getOrNull()
                    }
            }

            verifier(jwtService.verifier)

            validate { credential ->
                val id = credential.subject?.let { subject -> runCatching { Uuid.parse(subject) }.getOrNull() }
                    ?: return@validate null

                // Resolved here rather than at install time: reaching for the repository pulls
                // the database provider, and starting up must not block on schema creation.
                application.dependencies.resolve<UserRepository>().getById(id).first()
            }

            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized)
            }
        }
    }
}
