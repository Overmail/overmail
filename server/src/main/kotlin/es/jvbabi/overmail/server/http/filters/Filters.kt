package es.jvbabi.overmail.server.http.filters

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.SpamFilterRepository
import es.jvbabi.overmail.server.http.ForbiddenException
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/filters`. */
fun Route.filters() {

    authenticate(SESSION_AUTH) {
        /**
         * The caller's spam filters, oldest first, switched off ones included.
         *
         * The editor offers them as the filters a rule can be loaded from, so it wants all of
         * them: which ones are on says what happens to new mail, not what may be edited.
         */
        get {
            val user = call.principal<User>() ?: throw ForbiddenException("Not signed in")

            val filterRepository = application.dependencies.resolve<SpamFilterRepository>()
            val filters = filterRepository.getForUser(user).first()

            call.respond(FilterListResponse(filters = filters.map { it.toResponse() }))
        }
    }
}

/** The caller's filters, as `GET /api/filters` reports them. */
@Serializable
data class FilterListResponse(
    @SerialName("filters") val filters: List<FilterResponse>,
)
