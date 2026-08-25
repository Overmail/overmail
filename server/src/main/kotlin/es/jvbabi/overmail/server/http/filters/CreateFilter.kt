package es.jvbabi.overmail.server.http.filters

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.SpamFilterRepository
import es.jvbabi.overmail.server.http.ForbiddenException
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post

/** `POST /api/filters`. */
fun Route.createFilter() {

    authenticate(SESSION_AUTH) {
        /**
         * Writes a new spam filter for the caller and answers it, id and all.
         *
         * Nothing is applied to mail that is already there: the filter is what new mail is held
         * against from now on. Going back over the mailbox with it is its own request, see
         * `POST /api/filters/{id}/apply`.
         */
        post {
            val user = call.principal<User>() ?: throw ForbiddenException("Not signed in")
            val request = call.receiveFilter()

            val filterRepository = application.dependencies.resolve<SpamFilterRepository>()
            val filter = filterRepository.insert(
                user = user,
                name = request.name,
                rule = request.rule,
                isActive = request.isActive,
            )

            // The mail the rule was written for, if the caller named one.
            call.attributeSpamTo(filter, request.mail)

            call.respond(HttpStatusCode.Created, filter.toResponse())
        }
    }
}
