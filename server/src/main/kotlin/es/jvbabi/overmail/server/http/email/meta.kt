package es.jvbabi.overmail.server.http.email

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.User
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
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