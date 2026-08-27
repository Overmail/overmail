package es.jvbabi.overmail.server.http.webapp.email

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.SpamEntry
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.SpamRepository
import es.jvbabi.overmail.server.http.mails.MailResponse
import es.jvbabi.overmail.server.http.mails.toResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** `GET /api/webapp/email/{id}` as a websocket. */
fun Route.emailDetail() {

    authenticate(SESSION_AUTH) {
        /**
         * One mail with everything known about it, and again whenever any of that changes.
         *
         * A socket rather than a request, because the point of the screen is that it stays right:
         * the mail is filed, archived, flagged by a filter or unflagged again while it is open, and
         * every one of those is a row somebody else may write. The repositories already re-read on
         * a change, so this is one collect per open screen.
         *
         * Headers and state only. The body stays on `GET /api/mails/{id}/content` for the reason it
         * always has: it is big, the browser caches it, and nothing about it is live.
         *
         * Nothing is read up this socket and nothing about the agent goes down it -- that is its own
         * socket, see [emailAgent], and the split is what keeps this one simple. This one is a
         * subscription: it never asks anything of the screen, it never ends of its own accord, and
         * what it sends does not depend on anything the reader did. The agent is the opposite of
         * all three.
         *
         * Neither archived nor spam is left out here, unlike in the stack: this screen is how you
         * look at a mail, whatever was decided about it.
         *
         * A mail of somebody else reads as one that does not exist -- which ids are taken is not
         * the caller's business, and the summaries are already filtered by user, so it takes no
         * check of its own.
         */
        webSocket("/{id}") {
            // Inside `authenticate` there is a user, or the handshake never got here.
            val user = call.principal<User>()
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated"))

            val mailId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "mail is not an id"))

            val dependencies = call.application.dependencies
            val emailRepository = dependencies.resolve<EmailRepository>()
            val spamRepository = dependencies.resolve<SpamRepository>()

            val mail = emailRepository.getSummariesForUser(user, limit = 1, ids = listOf(mailId))
            val spam = spamRepository.getEntriesForEmail(mailId)

            combine(mail, spam) { page, entries -> page.mails.firstOrNull() to entries }
                .collect { (summary, entries) ->
                    if (summary == null) {
                        // Gone, or never theirs. Both end the same way: there is nothing to watch.
                        close(CloseReason(CloseReason.Codes.NORMAL, "no such mail"))
                        return@collect
                    }

                    sendEvent(
                        EmailDetailEvent.Mail(
                            mail = summary.toResponse(),
                            spam = entries.lastOrNull().toResponse(),
                        )
                    )
                }
        }
    }
}

/**
 * What the screen is sent. Told apart by `type`, as on the stack's socket, though there is one of
 * them: a second thing worth watching about a mail is a matter of time, and a screen that already
 * reads the discriminator does not have to be changed twice for it.
 */
@Serializable
sealed interface EmailDetailEvent {

    /** The mail with everything known about its state. */
    @Serializable
    @SerialName("mail")
    data class Mail(
        @SerialName("mail") val mail: MailResponse,
        @SerialName("spam") val spam: SpamStateResponse,
    ) : EmailDetailEvent
}

/**
 * Sends an event as an [EmailDetailEvent] rather than as whatever it happens to be. The type
 * argument is load bearing: it is what puts the `type` on the wire, and a subclass would go out
 * without one.
 */
private suspend fun DefaultWebSocketServerSession.sendEvent(event: EmailDetailEvent) {
    sendSerialized<EmailDetailEvent>(event)
}

/** Where a mail stands with spam, and what put it there. */
@Serializable
data class SpamStateResponse(
    @SerialName("is_spam") val isSpam: Boolean,
    /** When it was last flagged or unflagged, absent for a mail nobody ever flagged. */
    @SerialName("changed_at") val changedAt: String? = null,
    /** The filter that caught it, absent when a reader flagged it themselves. */
    @SerialName("filter") val filter: SpamFilterRefResponse? = null,
)

/** Just enough of a filter to name the one that caught a mail. */
@Serializable
data class SpamFilterRefResponse(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
)

/**
 * The newest entry of the mail's spam history as the state it left behind. No entry at all is a
 * mail nobody ever flagged, which reads as not spam and nothing else.
 */
private fun SpamEntry?.toResponse() = SpamStateResponse(
    isSpam = this?.isSpam ?: false,
    changedAt = this?.createdAt?.toString(),
    filter = this?.filter?.let { SpamFilterRefResponse(id = it.id.toString(), name = it.name) },
)
