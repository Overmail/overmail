package es.jvbabi.overmail.server.http.filters

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.spam.SpamRule
import es.jvbabi.overmail.server.domain.spam.SpamRuleMatcher
import es.jvbabi.overmail.server.http.ForbiddenException
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** `POST /api/filters/affected-mails`. */
fun Route.affectedMails() {

    authenticate(SESSION_AUTH) {
        /**
         * How many mails a rule would catch, over the caller's whole mailbox.
         *
         * Asked before a filter is saved: writing one while reading a mail is about that mail, and
         * a rule that also catches thirty others is something to say out loud first. `ignore_mail`
         * is the mail the rule was written for, so the count answers how many *others* there are.
         *
         * Nothing is written and nothing is flagged. Walks the mailbox, so it is not free -- one
         * request per save, not per keystroke.
         */
        post("/affected-mails") {
            val user = call.principal<User>() ?: throw ForbiddenException("Not signed in")

            val request = runCatching { call.receive<AffectedMailsRequest>() }.getOrNull()
                ?: throw BadRequestException("The body is not a rule to check")

            val ignored = request.ignoreMail?.let {
                runCatching { Uuid.parse(it) }.getOrNull()
                    ?: throw BadRequestException("`ignore_mail` is not a uuid")
            }

            val emailRepository = application.dependencies.resolve<EmailRepository>()
            val matcher = application.dependencies.resolve<SpamRuleMatcher>()

            var count = 0
            try {
                emailRepository.forEachRuleFacts(user) { id, facts ->
                    if (id != ignored && matcher.matches(request.rule, facts)) count++
                }
            } catch (_: IllegalArgumentException) {
                // A regex nothing can compile, the one thing about a rule that fails this late.
                throw BadRequestException("The rule holds a regex that cannot be compiled")
            }

            call.respond(AffectedMailsResponse(count = count))
        }
    }
}

/** A rule to hold against the mailbox, and the mail it was written for. */
@Serializable
data class AffectedMailsRequest(
    @SerialName("rule") val rule: SpamRule,
    /** Left out of the count, since the caller already knows about that one. */
    @SerialName("ignore_mail") val ignoreMail: String? = null,
)

/** How much a rule would catch, as `POST /api/filters/affected-mails` reports it. */
@Serializable
data class AffectedMailsResponse(
    @SerialName("count") val count: Int,
)
