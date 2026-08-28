package es.jvbabi.overmail.server.http.webapp.agent

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.ClassificationReason
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.AiQueueRepository
import es.jvbabi.overmail.server.domain.repository.EmailAiClassificationRepository
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.jobs.ai.AiProcessingState
import io.ktor.serialization.WebsocketDeserializeException
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** How many mails one press of a button puts in front of the agent. */
private const val BATCH = 10

/** `GET /api/webapp/agent` as a websocket. */
fun Route.agent() {

    authenticate(SESSION_AUTH) {
        /**
         * What the agent is doing, and the two ways of giving it something to do.
         *
         * Both on one socket, because they are two halves of one screen: the panel shows what is
         * owed and the button that adds to it, and a reader who presses it wants the count to move.
         * Split across a POST and a socket, the press would have to be reconciled with a push that
         * arrives a moment later.
         *
         * Reports rather than runs. Nothing here reads a mail -- pressing a button puts mails in the
         * queue and the walk over it happens elsewhere, see `AiMailProcessor`, which is what makes a
         * reader closing the panel harmless. The detail screen's own socket is the opposite: there
         * the run *is* the socket, because somebody is watching that one mail.
         *
         * Two sources, one frame. What is owed is a count over rows and comes from the queue; which
         * mail is open right now is not in the database at all and comes from a flow in memory, see
         * [AiProcessingState] -- and a screen wants to see them together or the spinner and the
         * count disagree for a moment.
         */
        webSocket("/agent") {
            // Inside `authenticate` there is a user, or the handshake never got here.
            val user = call.principal<User>()
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated"))

            val dependencies = call.application.dependencies
            val queue = dependencies.resolve<AiQueueRepository>()
            val emails = dependencies.resolve<EmailRepository>()
            val classifications = dependencies.resolve<EmailAiClassificationRepository>()
            val state = dependencies.resolve<AiProcessingState>()

            // The commands come in on their own coroutine: this socket is a subscription that also
            // takes orders, and a queue that pushes while nobody has asked anything must not be
            // held up waiting for a frame that may never come.
            val orders = launch {
                while (true) {
                    val command = try {
                        receiveDeserialized<AgentCommand>()
                    } catch (_: ClosedReceiveChannelException) {
                        return@launch
                    } catch (_: WebsocketDeserializeException) {
                        continue
                    } catch (_: SerializationException) {
                        continue
                    }

                    val wanted = when (command) {
                        // The newest of the mailbox, read again whether or not they have been read
                        // before: this is the button for "look at what just came in", and a mail the
                        // agent has already seen is exactly what somebody re-reads after a prompt
                        // has been changed.
                        is AgentCommand.ProcessNewest -> emails
                            .getSummariesForUser(user, limit = BATCH)
                            .first().mails.map { it.id }

                        // The newest that no run has ever touched, which is the button for catching
                        // up: it walks backwards through the mailbox one press at a time and never
                        // offers the same mail twice.
                        is AgentCommand.ProcessUnclassified ->
                            classifications.unclassifiedMails(user, limit = BATCH)
                    }

                    // Counted, because "10 queued" and "3 queued, 7 were already waiting" are
                    // different answers to the same press and a screen should not have to guess.
                    val queued = wanted.count { queue.enqueue(it, ClassificationReason.BULK_PROCESS) }

                    sendEvent(
                        AgentEvent.Queued(
                            asked = wanted.size,
                            queued = queued,
                            alreadyWaiting = wanted.size - queued,
                        )
                    )
                }
            }

            try {
                // No first tick of its own: `changesOf` re-emits for every consumer that subscribes,
                // which is what tells a screen where things stand rather than leaving it waiting for
                // the next change -- and on an empty queue the next change is never.
                queue.changes()
                    .map { queue.pendingFor(user) to queue.failedFor(user) }
                    .combine(state.current) { (pending, failed), current ->
                        AgentEvent.Queue(
                            pending = pending,
                            failed = failed,
                            // Only ever this reader's own mail: which mail the agent has open in
                            // somebody else's mailbox is not their business, and "the agent is busy
                            // elsewhere" is not something worth putting on a screen either.
                            current = current?.takeIf { it.userId == user.id }?.emailId?.toString(),
                        )
                    }
                    .collect { sendEvent(it) }
            } finally {
                orders.cancel()
            }
        }
    }
}

/**
 * What the screen is sent, told apart by `type` as on every other socket here.
 */
@Serializable
sealed interface AgentEvent {

    /** What the agent is doing: how much is owed, and which mail it has open. */
    @Serializable
    @SerialName("queue")
    data class Queue(
        /** Mails of this reader still waiting, the one in progress included. */
        @SerialName("pending") val pending: Int,
        /**
         * Mails the agent has given up on after failing on them. They are not waiting -- nothing
         * will take them again -- and they are reported because a queue that drops its failures
         * quietly is one nobody can tell from a queue that is being read.
         */
        @SerialName("failed") val failed: Int = 0,
        /** The mail being read right now, absent while the agent is between mails. */
        @SerialName("current") val current: String? = null,
    ) : AgentEvent

    /** The answer to a press of a button. */
    @Serializable
    @SerialName("queued")
    data class Queued(
        /** How many mails the request came to. Fewer than asked for where the mailbox ran out. */
        @SerialName("asked") val asked: Int,
        @SerialName("queued") val queued: Int,
        /** Of those, how many were in the queue already. */
        @SerialName("already_waiting") val alreadyWaiting: Int,
    ) : AgentEvent
}

/** What the screen sends up, told apart by `type`. */
@Serializable
sealed interface AgentCommand {

    /** Read the newest mails of the mailbox, whether or not they have been read before. */
    @Serializable
    @SerialName("process_newest")
    data object ProcessNewest : AgentCommand

    /** Read the newest mails that no run has ever touched. */
    @Serializable
    @SerialName("process_unclassified")
    data object ProcessUnclassified : AgentCommand
}

/**
 * Sends an event as an [AgentEvent] rather than as whatever it happens to be. The type argument is
 * load bearing: it is what puts the `type` on the wire, and a subclass would go out without one.
 */
private suspend fun DefaultWebSocketServerSession.sendEvent(event: AgentEvent) {
    sendSerialized<AgentEvent>(event)
}
