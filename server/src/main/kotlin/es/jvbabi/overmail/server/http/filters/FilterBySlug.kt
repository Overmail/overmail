package es.jvbabi.overmail.server.http.filters

import es.jvbabi.overmail.server.domain.models.SpamFilter
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.SpamFilterRepository
import es.jvbabi.overmail.server.http.ForbiddenException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * The filter the `{id}` of the path names. Throws rather than answering, see the status pages.
 */
internal suspend fun ApplicationCall.getFilterBySlug(): SpamFilter {
    val id = parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: throw BadRequestException("The id in the path is not a uuid")

    val filterRepository = application.dependencies.resolve<SpamFilterRepository>()

    return filterRepository.getById(id).first() ?: throw NotFoundException("No filter with id $id")
}

/** The same filter, and only if it is the caller's. Filters are per user and nobody else's. */
internal suspend fun ApplicationCall.getFilterBySlugWithRequiredPrincipalAsOwner(): SpamFilter {
    // Unreachable inside `authenticate`, which is where every route that reads a filter lives.
    val user = principal<User>() ?: throw ForbiddenException("Not signed in")

    val filter = getFilterBySlug()
    if (filter.user.id != user.id) throw ForbiddenException("Not the owner of filter ${filter.id}")

    return filter
}
