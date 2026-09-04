package es.jvbabi.overmail.server.http.email

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.User
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

suspend fun ApplicationCall.getMailFromRequest(): Email {
    val emailIdRaw = parameters["emailId"] ?: throw IllegalArgumentException("Missing emailId parameter")
    val emailId = Uuid.parseOrNull(emailIdRaw) ?: throw IllegalArgumentException("Invalid emailId parameter")

    val database = application.dependencies.resolve<OvermailDatabase>()
    return database.query {
        Email.findById(emailId) ?: throw IllegalArgumentException("Email not found")
    }
}

suspend fun ApplicationCall.getMailFromRequestWithOwnerCheck(): Email {
    val email = getMailFromRequest()
    val user = principal<User>() ?: throw IllegalArgumentException("User not authenticated")
    val database = application.dependencies.resolve<OvermailDatabase>()
    val isOwner = database.query {
        email.imapAccount.user.id.value == user.id.value
    }
    if (!isOwner) {
        throw IllegalArgumentException("User does not own this email")
    }
    return email
}

/**
 * The mail the route is about, if the id is one and it belongs to the caller. Null otherwise --
 * a malformed id, a mail that does not exist and one of another user are not told apart, so no
 * caller learns which mails there are.
 *
 * Columns only, unlike [getMailFromRequestWithOwnerCheck]: that one hands back the entity, which
 * reads the raw source of the mail with it, and a write that touches one flag has no use for it.
 */
suspend fun ApplicationCall.ownedEmailId(): Uuid? {
    val emailId = Uuid.parseOrNull(parameters["emailId"] ?: "") ?: return null
    val user = principal<User>() ?: return null

    val database = application.dependencies.resolve<OvermailDatabase>()
    val owned = database.query {
        Emails
            .leftJoin(ImapAccounts)
            .select(Emails.id)
            .where { (Emails.id eq emailId) and (ImapAccounts.user eq user.id) }
            .empty()
            .not()
    }

    return if (owned) emailId else null
}
