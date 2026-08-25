package es.jvbabi.overmail.server.http.mails

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
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
 * The mail the `{id}` of the path names.
 *
 * Throws rather than answering, so a route that needs a mail reads as one line: the status pages
 * turn a [BadRequestException] into a 400 and a [NotFoundException] into a 404.
 */
internal suspend fun ApplicationCall.getMailBySlug(): Email {
    val id = parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: throw BadRequestException("The id in the path is not a uuid")

    // Resolved per request rather than while the routes are built: reaching for the repository
    // pulls the database provider, and starting up must not wait on that.
    val emailRepository = application.dependencies.resolve<EmailRepository>()

    return emailRepository.getById(id).first() ?: throw NotFoundException("No mail with id $id")
}

/**
 * The same mail, and only if it is the caller's.
 *
 * A mail belongs to whoever owns the account it was imported into, and mails are nobody else's
 * business -- so anything a route does with one goes through here.
 */
internal suspend fun ApplicationCall.getMailBySlugWithRequiredPrincipalAsOwner(): Email {
    // Unreachable inside `authenticate`, which is where every route that reads a mail lives.
    val user = principal<User>() ?: throw ForbiddenException("Not signed in")

    val mail = getMailBySlug()
    if (mail.imapAccount.user.id != user.id) throw ForbiddenException("Not the owner of mail ${mail.id}")

    return mail
}
