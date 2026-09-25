package es.jvbabi.overmail.server.auth

import es.jvbabi.overmail.server.http.api.ApiErrorCode
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.respondApiError
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.HttpSecurityScheme
import io.ktor.server.application.Application
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.routing.openapi.registerSecurityScheme

/**
 * Turns the session token of a request into the user it was issued for, so a route inside
 * `authenticate { }` is never reached by a caller who is not signed in.
 *
 * The lookup itself is [authenticatedUserOrNull], which caches on the call -- the principal this
 * provider sets and the user a handler asks for are the same row, read once.
 */
class SessionAuthenticationProvider internal constructor(config: Config) : AuthenticationProvider(config) {

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call

        val cause =
            if (call.sessionToken() == null) AuthenticationFailedCause.NoCredentials
            else AuthenticationFailedCause.InvalidCredentials

        val user = call.authenticatedUserOrNull()
        if (user == null) {
            context.challengeUnauthorized(cause)
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
 * Describes the session provider to the OpenAPI spec. Ktor infers a scheme for its own providers
 * only, so without this every operation would require a `default` scheme that is defined nowhere.
 *
 * Declared as a bearer token because that is what Swagger UI can be given; the cookie carries the
 * same JWT and a browser sends it on its own.
 */
fun Application.registerSessionSecurityScheme() {
    registerSecurityScheme(
        providerName = null,
        securityScheme = HttpSecurityScheme(
            scheme = "bearer",
            bearerFormat = "JWT",
            description = "The session JWT, from the `$SESSION_COOKIE_NAME` cookie or an " +
                    "`Authorization: Bearer` header.",
        ),
    )
}

/**
 * No `WWW-Authenticate`: the token comes from a cookie, and a browser prompting for credentials is
 * the last thing this should trigger. The frontend reads the 401 and sends the user to /auth.
 *
 * The body is the api's own error shape, the same one a handler produces through
 * `unauthenticated()` -- a client parses one answer, not two.
 */
private fun AuthenticationContext.challengeUnauthorized(cause: AuthenticationFailedCause) {
    challenge(SessionAuthenticationProvider::class, cause) { challenge, call ->
        call.respondApiError(
            ApiException(
                status = HttpStatusCode.Unauthorized,
                code = ApiErrorCode.UNAUTHENTICATED,
                message = "This request needs a signed-in user",
            )
        )
        challenge.complete()
    }
}
