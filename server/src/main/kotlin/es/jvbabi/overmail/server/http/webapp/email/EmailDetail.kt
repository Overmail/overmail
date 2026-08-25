package es.jvbabi.overmail.server.http.webapp.email

import es.jvbabi.overmail.server.ai.MailAnalyst
import es.jvbabi.overmail.server.ai.MailContext
import es.jvbabi.overmail.server.ai.MailDirection
import es.jvbabi.overmail.server.ai.SENDER_STEP
import es.jvbabi.overmail.server.ai.MailParticipant as AiParticipant
import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.SpamEntry
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.SpamRepository
import es.jvbabi.overmail.server.http.mails.MailResponse
import es.jvbabi.overmail.server.domain.spam.toRuleFacts
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
         * A mail of somebody else closes the socket like one that does not exist -- which ids are
         * taken is not the caller's business.
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
            val analyst = dependencies.resolve<MailAnalyst>()

            // Read once, and with it the check that the mail is the caller's: the flows below
            // filter by user too, but the agent is handed the mail itself and must not be handed
            // somebody else's.
            val email = emailRepository.getById(mailId).first()
            if (email == null || email.imapAccount.user.id != user.id) {
                return@webSocket close(CloseReason(CloseReason.Codes.NORMAL, "no such mail"))
            }

            // Its own coroutine: a model takes seconds, and the state below must not wait for it.
            // Once per open screen -- nothing about the answer changes while the mail is read, and
            // asking again on every state change would spend a request per tag somebody files.
            launch {
                val analysis = analyst.run(SENDER_STEP, email.asAnalysisContext(user))

                sendEvent(
                    EmailDetailEvent.Sender(
                        person = analysis.value?.person,
                        organisation = analysis.value?.organisation,
                        failure = analysis.failure,
                    )
                )
            }

            // Neither archived nor spam is left out here, unlike in the stack: this screen is how
            // you look at a mail, whatever was decided about it.
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
 * What the screen is sent. Told apart by `type`, as on the stack's socket: the mail and its state
 * arrive on opening and on every change, and the agent's reading of the mail arrives once, whenever
 * it is done.
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

    /**
     * Who the agent reads as the sender. Both fields are nullable and both are meant to be, see
     * [es.jvbabi.overmail.server.ai.SenderAnalysis]; `failure` says why there is nothing when the
     * model could not be asked at all.
     */
    @Serializable
    @SerialName("sender_analysis")
    data class Sender(
        @SerialName("person") val person: String? = null,
        @SerialName("organisation") val organisation: String? = null,
        @SerialName("failure") val failure: String? = null,
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

/**
 * The mail as the analysis steps see it, see [MailContext].
 *
 * The body is the one a rule is held against as well: the text part, or the HTML flattened -- a
 * model handed raw markup spends its attention on tags, see `mailFactsOf`.
 */
private fun Email.asAnalysisContext(owner: User) = MailContext(
    owner = AiParticipant(name = owner.name, address = owner.email),
    direction = MailDirection.of(
        ownerAddress = owner.email,
        senderAddress = sender.address,
        recipientAddresses = recipients.map { it.emailUser.address },
    ),
    sender = AiParticipant(name = senderName, address = sender.address),
    // Everyone the mail names, cc and bcc included: who else was written to is part of reading it.
    recipients = recipients.map { AiParticipant(name = it.name, address = it.emailUser.address) },
    subject = subject,
    body = toRuleFacts().body,
)
