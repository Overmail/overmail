package es.jvbabi.overmail.server.http.webapp.mystack

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.http.mails.MailResponse
import es.jvbabi.overmail.server.http.mails.toInstantOrNull
import es.jvbabi.overmail.server.http.mails.toResponse
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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/** How many mails a pack holds when the client does not say. One stack's worth. */
private const val DEFAULT_LIMIT = 10

/**
 * The most a pack can hold. Far below the listing's cap on purpose: the stack is worked through one
 * mail at a time, so a client asking for hundreds is a client that misunderstood the screen.
 */
private const val MAX_LIMIT = 100

/**
 * The stack screen's own channel.
 *
 * Everything the screen does with mails goes over this socket -- for now that is asking for the
 * next pack, which is why it is a socket rather than a listing endpoint: the decisions the reader
 * makes and the mails that arrive while they work go the same way, and both want a connection that
 * is already open. Bodies are the one exception, they stay on `GET /api/mails/{id}/content`: they
 * are big, they are cached by the browser, and nothing about them is live.
 */
fun Route.myStack() {

    authenticate(SESSION_AUTH) {
        /**
         * The stack's socket. Commands and events are JSON objects told apart by a `type`, see
         * [StackCommand] and [StackEvent]; every command is answered with exactly one event, in
         * the order the commands came in.
         *
         * The session is the caller's, taken from the same cookie the rest of the API runs on, so
         * a socket can only ever read the mail of whoever opened it.
         */
        webSocket {
            // Inside `authenticate` there is a user, or the handshake never got here.
            val user = call.principal<User>()
                ?: return@webSocket close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated")
                )

            // Resolved once per socket rather than per command: reaching for the repository pulls
            // the database provider, and a socket lives for as long as the screen is open.
            val emailRepository = call.application.dependencies.resolve<EmailRepository>()

            while (true) {
                val command = try {
                    receiveDeserialized<StackCommand>()
                } catch (_: ClosedReceiveChannelException) {
                    // The screen went away; nothing to clean up, the session ends with this block.
                    return@webSocket
                } catch (_: WebsocketDeserializeException) {
                    // A frame the converter could not take at all, e.g. a binary one.
                    sendEvent(StackEvent.Failed(message = "unreadable command"))
                    continue
                } catch (_: SerializationException) {
                    // Readable but not a command: malformed JSON, or a `type` this server does not
                    // know. Said out loud rather than dropped, and the socket stays open -- one
                    // frame nobody can read is no reason to make a client reconnect.
                    sendEvent(StackEvent.Failed(message = "unreadable command"))
                    continue
                }

                when (command) {
                    is StackCommand.LoadMails -> sendEvent(emailRepository.packFor(user, command))
                }
            }
        }
    }
}

/**
 * The reader's next pack of mails, or what was wrong with the command.
 *
 * The same window the listing serves, cut the way the stack reads it: newest first, and [before]
 * exclusive so the `sent_at` of the oldest mail the client holds is the cursor for the next pack.
 */
private suspend fun EmailRepository.packFor(user: User, command: StackCommand.LoadMails): StackEvent {
    val limit = command.limit.takeIf { it in 1..MAX_LIMIT }
        ?: return StackEvent.Failed(LOAD_MAILS, "limit has to be between 1 and $MAX_LIMIT")

    // Told apart from an absent cursor: an unreadable one is the client's mistake, and answering
    // with the newest mails would hide it behind a pack the client already has.
    val before = command.before?.let {
        it.toInstantOrNull() ?: return StackEvent.Failed(LOAD_MAILS, "before is not a timestamp")
    }

    val page = getSummariesForUser(user, limit, before = before).first()

    return StackEvent.Mails(
        mails = page.mails.map { it.toResponse() },
        total = page.total,
        before = command.before,
    )
}

/**
 * Sends an event as a [StackEvent] rather than as whatever it happens to be. The type argument is
 * load bearing: it is what puts the `type` on the wire, and a subclass would go out without one.
 */
private suspend fun DefaultWebSocketServerSession.sendEvent(event: StackEvent) {
    sendSerialized<StackEvent>(event)
}

/** The `type` of the one command there is, so the failures can name it. */
private const val LOAD_MAILS = "load_mails"

/** What the stack screen sends up the socket. */
@Serializable
sealed interface StackCommand {

    /**
     * Asks for the next pack of mails, newest first.
     *
     * `before` is the exclusive upper bound on the send time -- the `sent_at` of the oldest mail
     * the client holds -- and absent for the first pack. Send times are stored at second
     * precision, so a cursor cutting inside a second is the client's business, see the listing.
     */
    @Serializable
    @SerialName(LOAD_MAILS)
    data class LoadMails(
        @SerialName("limit") val limit: Int = DEFAULT_LIMIT,
        @SerialName("before") val before: String? = null,
    ) : StackCommand
}

/** What the server sends down the socket. */
@Serializable
sealed interface StackEvent {

    /** A pack of mails, without their bodies, exactly as the listing reports them. */
    @Serializable
    @SerialName("mails")
    data class Mails(
        @SerialName("mails") val mails: List<MailResponse>,
        /** Mails matching the window the pack was cut out of, this pack included. */
        @SerialName("total") val total: Int,
        /** The cursor the pack was read with, echoed so a client can tell what it answers. */
        @SerialName("before") val before: String? = null,
    ) : StackEvent

    /** A command that could not be carried out. The socket stays open. */
    @Serializable
    @SerialName("error")
    data class Failed(
        /** The `type` of the command, absent when the frame could not even be read. */
        @SerialName("command") val command: String? = null,
        @SerialName("message") val message: String,
    ) : StackEvent
}
