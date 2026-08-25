package es.jvbabi.overmail.server.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

/** A request that is authenticated but not allowed. */
class ForbiddenException(message: String) : Exception(message)

/**
 * Turns the exceptions a route may throw into the status it meant.
 *
 * This is what lets a lookup like `getMailBySlug` hand a route the thing it asked for or nothing
 * at all, instead of every route spelling out the same three answers. Only these three are mapped:
 * anything else is a fault of ours and stays a 500.
 *
 * No bodies. What went wrong is the caller's own request, and a message would only say back what
 * they sent.
 */
internal fun Application.installStatusPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        exception<NotFoundException> { call, _ -> call.respond(HttpStatusCode.NotFound) }
        exception<ForbiddenException> { call, _ -> call.respond(HttpStatusCode.Forbidden) }
    }
}
