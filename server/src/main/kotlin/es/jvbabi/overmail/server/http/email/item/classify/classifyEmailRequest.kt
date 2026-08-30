package es.jvbabi.overmail.server.http.email.item.classify

import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.email.getMailFromRequestWithOwnerCheck
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post

fun Route.classifyEmailRequest() {
    authenticate {
        post {
            val emailClassificationQueue = application.dependencies.resolve<EmailClassificationQueue>()

            val user = call.principal<User>() ?: error("User not authenticated")
            val email = call.getMailFromRequestWithOwnerCheck()

            emailClassificationQueue.enqueue(emailId = email.id.value)

            call.respond(status = HttpStatusCode.NoContent, message = "Email classified")
        }
    }
}