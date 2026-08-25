package es.jvbabi.overmail.server.http.filters

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.SpamRepository
import es.jvbabi.overmail.server.domain.spam.SpamRuleMatcher
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** `POST /api/filters/{id}/apply`. */
fun Route.applyFilter() {

    authenticate(SESSION_AUTH) {
        /**
         * Holds one filter against the mails that are already there and flags what it catches.
         *
         * This is the "and go back over the mailbox" half of saving a filter -- saving one on its
         * own only decides what happens to mail from now on. Reports how many mails it flagged,
         * which is fewer than the filter catches when some of them were spam already.
         */
        post("/{id}/apply") {
            val filter = call.getFilterBySlugWithRequiredPrincipalAsOwner()

            val emailRepository = application.dependencies.resolve<EmailRepository>()
            val spamRepository = application.dependencies.resolve<SpamRepository>()
            val matcher = application.dependencies.resolve<SpamRuleMatcher>()

            // Collected first and flagged afterwards: flagging writes, and writing while the walk
            // is still reading would hold two transactions open on the same mailbox.
            val caught = mutableListOf<Uuid>()
            try {
                emailRepository.forEachRuleFacts(filter.user) { id, facts ->
                    if (matcher.matches(filter.rule, facts)) caught.add(id)
                }
            } catch (_: IllegalArgumentException) {
                throw BadRequestException("The filter holds a regex that cannot be compiled")
            }

            // Mails that were spam already come back null and are not counted: this reports what
            // changed, not what the filter matches.
            val flagged = caught.count { spamRepository.setSpam(it, isSpam = true, filterId = filter.id) != null }

            call.respond(ApplyFilterResponse(flagged = flagged))
        }
    }
}

/** What applying a filter did, as `POST /api/filters/{id}/apply` reports it. */
@Serializable
data class ApplyFilterResponse(
    @SerialName("flagged") val flagged: Int,
)
