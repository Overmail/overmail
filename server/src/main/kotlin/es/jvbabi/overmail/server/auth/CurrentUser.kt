package es.jvbabi.overmail.server.auth

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.util.AttributeKey

private const val BEARER_PREFIX = "Bearer "

/** Where the resolved user is kept for the rest of the call, see [authenticatedUserOrNull]. */
private val SESSION_USER = AttributeKey<User>("overmail.session-user")

/**
 * The signed-in user of this request, or null when there is none.
 *
 * Resolved once and kept on the call from there on. A handler asks for the user directly, and
 * again through every `requireOwned...FromUrl` it uses -- that is one lookup per request, not one
 * per question.
 *
 * Two ways in, in this order: the principal an `authenticate { }` block put there, and the session
 * token of the request itself. The second is what lets a route that is mounted outside
 * `authenticate { }` still know who is calling; it is the same token, read the same way.
 *
 * The result is a DAO entity from a transaction that is already over: `username`, `email` and `id`
 * were read with the row and stay readable, everything else needs `query { }`.
 */
suspend fun ApplicationCall.authenticatedUserOrNull(): User? {
    attributes.getOrNull(SESSION_USER)?.let { return it }

    val user = principal<User>() ?: sessionUserFromToken() ?: return null
    attributes.put(SESSION_USER, user)
    return user
}

/**
 * The user the session token of this request was issued for.
 *
 * [JwtService] decides whether the token is genuine and unexpired; the user is then loaded, so a
 * token for a meanwhile deleted account authenticates nobody.
 */
private suspend fun ApplicationCall.sessionUserFromToken(): User? {
    val token = sessionToken() ?: return null

    // Resolved per call, not at install time: pulling the database out of the container eagerly
    // would create the schema while the application is still being set up.
    val userId = application.dependencies.resolve<JwtService>().userIdOf(token) ?: return null
    return application.dependencies.resolve<OvermailDatabase>().query { User.findById(userId) }
}

/**
 * The token of the request: the cookie a browser sends on its own -- websocket handshakes
 * included -- or an `Authorization: Bearer` header for clients that have no cookie jar.
 */
internal fun ApplicationCall.sessionToken(): String? =
    request.cookies[SESSION_COOKIE_NAME]
        ?: request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
