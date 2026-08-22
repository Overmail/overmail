package es.jvbabi.overmail.server.http.webapp.agent

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.AgentQueueMode
import es.jvbabi.overmail.server.domain.models.AgentStep
import es.jvbabi.overmail.server.domain.models.AgentStatus
import es.jvbabi.overmail.server.domain.models.AgentWork
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.AgentRepository
import es.jvbabi.overmail.server.http.mails.ParticipantResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.Route
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Watching the mail agent work, for the progress display the web app puts on the screen. */
fun Route.agentProcess() {

    authenticate(SESSION_AUTH) {
        /**
         * Pushes what the agent is doing, as often as it changes: how much of the caller's mailbox
         * is behind it and how much is still in front, and which mail it has in its hands.
         *
         * The current state is sent the moment the socket opens, so a caller need not ask for it
         * separately. Nothing is read from the socket -- whatever a client sends is ignored.
         *
         * One agent serves the whole installation, so it may well be working on a mail of somebody
         * else; that is what `pending` means, see [AgentWorkState].
         */
        webSocket {
            // Inside `authenticate` there is a user, or the handshake never got here.
            val user = call.principal<User>()
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))

            // Resolved per connection rather than while the routes are built: reaching for the
            // repository pulls the database provider, and starting up must not wait on that.
            val agentRepository = call.application.dependencies.resolve<AgentRepository>()

            agentRepository.getStatusForUser(user).collect { sendSerialized(it.asResponse()) }
        }
    }
}

private fun AgentStatus.asResponse() = AgentProcessStatus(
    queue = AgentQueueStatus(
        mode = when (queue.mode) {
            AgentQueueMode.BACKLOG -> ReportedQueueMode.BACKLOG
            AgentQueueMode.LIVE -> ReportedQueueMode.LIVE
        },
        processed = queue.processed,
        queued = queue.queued,
        oldestQueuedAt = queue.oldestQueued?.toString(),
    ),
    work = when (val work = work) {
        AgentWork.Idle -> AgentWorkStatus(state = AgentWorkState.IDLE)
        AgentWork.Pending -> AgentWorkStatus(state = AgentWorkState.PENDING)
        is AgentWork.Processing -> AgentWorkStatus(
            state = AgentWorkState.PROCESSING,
            emailId = work.emailId.toString(),
            subject = work.subject,
            sender = ParticipantResponse(address = work.sender.address, name = work.sender.name),
            step = when (work.step) {
                AgentStep.ORIGIN -> ReportedStep.ORIGIN
                AgentStep.TAGS -> ReportedStep.TAGS
                AgentStep.THREAD -> ReportedStep.THREAD
                AgentStep.REVIEW -> ReportedStep.REVIEW
            },
        )
    },
)

/** What `/api/webapp/agent/process` pushes on every change. */
@Serializable
data class AgentProcessStatus(
    @SerialName("queue") val queue: AgentQueueStatus,
    @SerialName("work") val work: AgentWorkStatus,
)

/** The caller's share of the queue: how far the agent has come through their mailbox. */
@Serializable
data class AgentQueueStatus(
    @SerialName("mode") val mode: ReportedQueueMode,
    /** Mails of the caller the agent has been through. */
    @SerialName("processed") val processed: Int,
    /** Mails of the caller still waiting, the one being worked on right now included. */
    @SerialName("queued") val queued: Int,
    /** Send time of the oldest waiting mail as an ISO-8601 instant, null when none is waiting. */
    @SerialName("oldest_queued_at") val oldestQueuedAt: String?,
)

/** Whether what is left is a mailbox being worked through or the day's post. */
@Serializable
enum class ReportedQueueMode {
    /** The oldest waiting mail is over a week old: a freshly imported mailbox is being caught up. */
    @SerialName("backlog")
    BACKLOG,

    /** Nothing old is waiting, so at most recent mail is being worked through. */
    @SerialName("live")
    LIVE,
}

/**
 * What the agent has in its hands, seen from the caller.
 *
 * Everything below [state] describes the caller's own mail and is null in every other state: on a
 * foreign mail the agent is reported as busy and nothing else, see [AgentWorkState.PENDING].
 */
@Serializable
data class AgentWorkStatus(
    @SerialName("state") val state: AgentWorkState,
    @SerialName("email_id") val emailId: String? = null,
    /** Empty for a mail that carries no subject line, as mails are stored. */
    @SerialName("subject") val subject: String? = null,
    @SerialName("sender") val sender: ParticipantResponse? = null,
    /** Which pass the mail is in right now; a mail goes through several. */
    @SerialName("step") val step: ReportedStep? = null,
)

/** The passes a mail goes through, in the order it goes through them. */
@Serializable
enum class ReportedStep {
    /** Reading off who the mail came from. */
    @SerialName("origin")
    ORIGIN,

    /** Suggesting what to file it under. */
    @SerialName("tags")
    TAGS,

    /** Working out which matter it continues. */
    @SerialName("thread")
    THREAD,

    /** Going over the filing again with the neighbouring mails in view. */
    @SerialName("review")
    REVIEW,
}

/** @see AgentWorkStatus */
@Serializable
enum class AgentWorkState {
    /** The agent has nothing to do, or is down. */
    @SerialName("idle")
    IDLE,

    /** The agent is busy with a mail of somebody else, so the caller's mails wait their turn. */
    @SerialName("pending")
    PENDING,

    /** The agent is working on a mail of the caller, named in `email_id`. */
    @SerialName("processing")
    PROCESSING,
}
