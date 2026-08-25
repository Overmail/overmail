package es.jvbabi.overmail.server.http.filters

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.repository.SpamFilterRepository
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.put

/** `PUT /api/filters/{id}`. */
fun Route.updateFilter() {

    authenticate(SESSION_AUTH) {
        /**
         * Overwrites what one filter says: its name, its rule and whether it is switched on.
         *
         * Replaces rather than patches, which is what the editor does with it -- it loads a filter,
         * changes the blocks and saves the whole thing back. What the filter has already caught
         * stays caught either way, and nothing is applied to mail that is already there.
         */
        put("/{id}") {
            // Reads the filter first, so somebody else's answers before the body is even looked at.
            val existing = call.getFilterBySlugWithRequiredPrincipalAsOwner()
            val request = call.receiveFilter()

            val filterRepository = application.dependencies.resolve<SpamFilterRepository>()
            val filter = filterRepository.update(
                id = existing.id,
                name = request.name,
                rule = request.rule,
                isActive = request.isActive,
            ) ?: throw NotFoundException("No filter with id ${existing.id}")

            call.respond(filter.toResponse())
        }
    }
}
