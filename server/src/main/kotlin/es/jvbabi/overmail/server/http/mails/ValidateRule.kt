package es.jvbabi.overmail.server.http.mails

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.spam.SpamRule
import es.jvbabi.overmail.server.domain.spam.SpamRuleMatcher
import io.ktor.server.auth.authenticate
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

/** `POST /api/mails/{id}/validate-rule`. */
fun Route.validateRule() {

    authenticate(SESSION_AUTH) {
        /**
         * Whether a spam rule holds for one mail, with the rule itself as the body.
         *
         * The editor sends the rule it currently shows after every change, so that whoever is
         * writing it can see whether it would catch the mail in front of them. Nothing is stored
         * and nothing is filed: this only answers the question.
         *
         * A rule the reader cannot make sense of -- an unknown operator, a regex that will not
         * compile -- is the caller's mistake and answers 400.
         */
        post("/{id}/validate-rule") {
            val rule = runCatching { call.receive<SpamRule>() }.getOrNull()
                ?: throw BadRequestException("The body is not a spam rule")

            val email = call.getMailBySlugWithRequiredPrincipalAsOwner()

            val matcher = application.dependencies.resolve<SpamRuleMatcher>()
            val matches = try {
                matcher.matches(rule, email)
            } catch (_: IllegalArgumentException) {
                // The only thing in a rule that can fail this late is a regex nothing compiles.
                throw BadRequestException("The rule holds a regex that cannot be compiled")
            }

            call.respond(RuleMatchResponse(matches = matches))
        }
    }
}

/** Whether a rule holds, as `POST /api/mails/{id}/validate-rule` reports it. */
@Serializable
data class RuleMatchResponse(
    @SerialName("matches") val matches: Boolean,
)
