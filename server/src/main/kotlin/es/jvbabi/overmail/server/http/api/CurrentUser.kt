package es.jvbabi.overmail.server.http.api

import es.jvbabi.overmail.server.auth.authenticatedUserOrNull
import es.jvbabi.overmail.server.database.models.User
import io.ktor.server.application.ApplicationCall

/**
 * Who is calling, or 401.
 *
 * This is how a handler gets at the user: it either returns one or the request is over, so no
 * route carries a null case it cannot do anything with anyway. Ask as often as you like -- the
 * user is resolved once per request and kept on the call, see `authenticatedUserOrNull`.
 *
 * 401 and not 403, even though the caller is refused either way: the frontend sends a caller to
 * /auth on this status, and "your session ran out" is exactly the case that has to land there.
 * 403 is what a *signed-in* caller gets for a resource that is not theirs, see [forbidden].
 */
suspend fun ApplicationCall.requireAuthenticatedUser(): User =
    authenticatedUserOrNull() ?: unauthenticated()

/** The id of [requireAuthenticatedUser], which is what most queries actually want. */
suspend fun ApplicationCall.requireAuthenticatedUserId(): User.Id =
    requireAuthenticatedUser().id.value
