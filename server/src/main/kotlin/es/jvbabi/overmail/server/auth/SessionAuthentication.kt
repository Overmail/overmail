package es.jvbabi.overmail.server.auth

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond

private const val BEARER_PREFIX = "Bearer "

/**
 * Turns the session token of a request into the [User] it was issued for, so a route inside
 * `authenticate { }` can rely on there being one and read it through [user].
 *
 * The token is the JWT from the sign-in flow, taken from the session cookie the browser sends on
 * its own -- websocket handshakes included -- or from an `Authorization: Bearer` header for
 * clients that have no cookie jar. [JwtService] decides whether it is genuine and unexpired; the
 * user is then loaded, so a token for a meanwhile deleted account authenticates nobody.
 *
 * The principal is the DAO entity, loaded in a transaction that is over by the time a route sees
 * it. Its own columns (`username`, `email`, `id`) were read with the row and stay readable;
 * anything that hits the database again -- a reference, a write, `refresh()` -- has to happen
 * inside `OvermailDatabase.query { }`.
 */
class SessionAuthenticationProvider internal constructor(config: Config) : AuthenticationProvider(config) {

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call

        val token = call.sessionToken()
        if (token == null) {
            context.challengeUnauthorized(AuthenticationFailedCause.NoCredentials)
            return
        }

        // Resolved per call, not at install time: pulling the database out of the container
        // eagerly would create the schema while the application is still being set up.
        val userId = call.application.dependencies.resolve<JwtService>().userIdOf(token)
        val user = userId?.let { id ->
            call.application.dependencies.resolve<OvermailDatabase>().query { User.findById(id) }
        }

        if (user == null) {
            context.challengeUnauthorized(AuthenticationFailedCause.InvalidCredentials)
            return
        }

        context.principal(user)
    }

    class Config internal constructor(name: String?) : AuthenticationProvider.Config(name)
}

/**
 * Registers the session provider. Unnamed by default, so `authenticate { }` without an argument
 * picks it up -- it is the only way into this API.
 */
fun AuthenticationConfig.overmailSession(name: String? = null) {
    register(SessionAuthenticationProvider(SessionAuthenticationProvider.Config(name)))
}

/**
 * The signed-in user of the current request. Only inside a non-optional `authenticate { }`:
 * anywhere else there is nobody to return.
 */
val ApplicationCall.user: User
    get() = principal<User>()
        ?: error("no authenticated user on this call -- is the route inside authenticate { }?")

private fun ApplicationCall.sessionToken(): String? =
    request.cookies[SESSION_COOKIE_NAME]
        ?: request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

/**
 * No `WWW-Authenticate`: the token comes from a cookie, and a browser prompting for credentials
 * is the last thing this should trigger. The frontend reads the 401 and sends the user to /auth.
 */
private fun AuthenticationContext.challengeUnauthorized(cause: AuthenticationFailedCause) {
    challenge(SessionAuthenticationProvider::class, cause) { challenge, call ->
        call.respond(HttpStatusCode.Unauthorized)
        challenge.complete()
    }
}
