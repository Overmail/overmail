package es.jvbabi.overmail.server.http.webapp.mystack

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.MailSummary
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.http.mails.MailResponse
import es.jvbabi.overmail.server.http.mails.TagResponse
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
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** How many mails an answer holds when the client does not say. One stack's worth. */
private const val DEFAULT_LIMIT = 10

/**
 * The most one answer can hold. Far below the listing's cap on purpose: the stack is worked through
 * one mail at a time, so a client asking for hundreds is a client that misunderstood the screen.
 */
private const val MAX_LIMIT = 100

/** The most tags one mail can be filed under in one go. A guard, not a rule anyone should meet. */
private const val MAX_TAGS = 50

/** The `type` of each command, so the failures can name the one they belong to. */
private const val LOAD_MAILS = "load_mails"
private const val SET_TAGS = "set_tags"

/**
 * The stack screen's own channel.
 *
 * Everything the screen does with mails goes over this socket: asking for the next mails, and
 * filing them under tags. Bodies are the one exception, they stay on
 * `GET /api/mails/{id}/content` -- they are big, the browser caches them, and nothing about them is
 * live.
 *
 * Two directions, told apart by whether an event carries `reply_to`: a command is answered with
 * exactly one event carrying its `id`, and everything without one is the server keeping the screen
 * in sync on its own. The tag list is the latter -- it arrives when the socket opens and again on
 * every change, so the autocomplete is never out of date and nobody has to ask for it.
 */
fun Route.myStack() {

    authenticate(SESSION_AUTH) {
        /**
         * The stack's socket. Commands and events are JSON objects told apart by a `type`, see
         * [StackCommand] and [StackEvent].
         *
         * The session is the caller's, taken from the same cookie the rest of the API runs on, so
         * a socket can only read and write the mail of whoever opened it.
         */
        webSocket {
            // Inside `authenticate` there is a user, or the handshake never got here.
            val user = call.principal<User>()
                ?: return@webSocket close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated")
                )

            // Resolved once per socket rather than per command: reaching for a repository pulls the
            // database provider, and a socket lives for as long as the screen is open.
            val dependencies = call.application.dependencies
            val emailRepository = dependencies.resolve<EmailRepository>()
            val tagRepository = dependencies.resolve<TagRepository>()

            // The caller's tags, now and whenever they change. Its own coroutine, because it
            // outlives any one command; it ends with the session, which is what this scope is.
            launch {
                tagRepository.getForUser(user).collect { tags ->
                    sendEvent(StackEvent.Tags(tags.map { TagResponse(it.id.toString(), it.name) }))
                }
            }

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

                val answer = when (command) {
                    is StackCommand.LoadMails -> emailRepository.mailsFor(user, command)
                    is StackCommand.SetTags -> setTags(user, command, emailRepository, tagRepository)
                }

                sendEvent(answer)
            }
        }
    }
}

/**
 * The reader's next mails, or what was wrong with the command.
 *
 * The same window the listing serves, cut the way the stack reads it: newest first, and `before`
 * exclusive so the `sent_at` of the oldest mail the client holds is the cursor for the next ask.
 */
private suspend fun EmailRepository.mailsFor(user: User, command: StackCommand.LoadMails): StackEvent {
    val limit = command.limit.takeIf { it in 1..MAX_LIMIT }
        ?: return StackEvent.Failed(command.id, LOAD_MAILS, "limit has to be between 1 and $MAX_LIMIT")

    // Told apart from an absent cursor: an unreadable one is the client's mistake, and answering
    // with the newest mails would hide it behind mails the client already has.
    val before = command.before?.let {
        it.toInstantOrNull()
            ?: return StackEvent.Failed(command.id, LOAD_MAILS, "before is not a timestamp")
    }

    val page = getSummariesForUser(user, limit, before = before).first()

    return StackEvent.Mails(
        replyTo = command.id,
        mails = page.mails.map { it.toResponse() },
        total = page.total,
        before = command.before,
    )
}

/**
 * Files one mail under exactly the tags named, creating the ones the user does not have yet.
 *
 * Names rather than ids, because that is what the reader types: a tag they have is looked up, one
 * they do not is made. Stated as the whole set the mail should carry, so the same command run twice
 * leaves the same thing behind -- which is what lets a client resend it after a reconnect without
 * having to know whether the first one landed.
 *
 * The answer carries the tags the mail ends up with, so the client shows the stored spelling rather
 * than what was typed.
 */
private suspend fun setTags(
    user: User,
    command: StackCommand.SetTags,
    emailRepository: EmailRepository,
    tagRepository: TagRepository,
): StackEvent {
    val mailId = runCatching { Uuid.parse(command.mail) }.getOrNull()
        ?: return StackEvent.Failed(command.id, SET_TAGS, "mail is not an id")

    if (command.tags.size > MAX_TAGS) {
        return StackEvent.Failed(command.id, SET_TAGS, "a mail takes at most $MAX_TAGS tags")
    }

    // Doubles as the check that the mail is the caller's: the listing only ever reports their own,
    // so a mail of somebody else is a mail that does not exist.
    val before = emailRepository.summaryOf(user, mailId)
        ?: return StackEvent.Failed(command.id, SET_TAGS, "no such mail")

    // Blank ones dropped and doubles thrown out the way the store matches them, without regard to
    // case: the reader typing "rechnung" means the "Rechnung" they already have.
    val wanted = command.tags
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
    val wantedKeys = wanted.map { it.lowercase() }.toSet()

    for (filed in before.tags) {
        if (filed.tag.name.lowercase() !in wantedKeys) tagRepository.detach(mailId, filed.tag)
    }

    val filedKeys = before.tags.map { it.tag.name.lowercase() }.toSet()
    for (name in wanted) {
        if (name.lowercase() in filedKeys) continue

        val tag = tagRepository.findOrCreate(user, name, createdByAgent = false)
        // No reason: the reader picked the tag, they did not explain it.
        tagRepository.attach(mailId, tag, reason = null, createdByAgent = false)
    }

    // Read back rather than assembled from what was written: the answer should be what the database
    // holds, including a tag that was already there under a different spelling.
    val after = emailRepository.summaryOf(user, mailId)

    return StackEvent.MailTags(
        replyTo = command.id,
        mail = command.mail,
        tags = after?.tags.orEmpty().map { TagResponse(it.tag.id.toString(), it.tag.name) },
    )
}

/** One of the caller's mails as the listing reports it, or null if it is not theirs. */
private suspend fun EmailRepository.summaryOf(user: User, mailId: Uuid): MailSummary? =
    getSummariesForUser(user, limit = 1, ids = listOf(mailId)).first().mails.firstOrNull()

/**
 * Sends an event as a [StackEvent] rather than as whatever it happens to be. The type argument is
 * load bearing: it is what puts the `type` on the wire, and a subclass would go out without one.
 */
private suspend fun DefaultWebSocketServerSession.sendEvent(event: StackEvent) {
    sendSerialized<StackEvent>(event)
}

/** What the stack screen sends up the socket. */
@Serializable
sealed interface StackCommand {

    /**
     * The client's number for this command, echoed on the answer as `reply_to`. May be left out --
     * the answer then carries none, which is enough for a client that asks one thing at a time.
     */
    val id: Long?

    /**
     * Asks for the next mails, newest first.
     *
     * `before` is the exclusive upper bound on the send time -- the `sent_at` of the oldest mail
     * the client holds -- and absent for the first ask. Send times are stored at second precision,
     * so a cursor cutting inside a second is the client's business, see the listing.
     */
    @Serializable
    @SerialName(LOAD_MAILS)
    data class LoadMails(
        @SerialName("id") override val id: Long? = null,
        @SerialName("limit") val limit: Int = DEFAULT_LIMIT,
        @SerialName("before") val before: String? = null,
    ) : StackCommand

    /**
     * Files one mail under exactly these tags, by name. Tags the user does not have yet are
     * created; tags the mail carries and this list does not name are taken off it.
     */
    @Serializable
    @SerialName(SET_TAGS)
    data class SetTags(
        @SerialName("id") override val id: Long? = null,
        @SerialName("mail") val mail: String,
        @SerialName("tags") val tags: List<String>,
    ) : StackCommand
}

/** What the server sends down the socket. */
@Serializable
sealed interface StackEvent {

    /**
     * The command this answers, absent on the events the server sends on its own. A client tells
     * the two apart by this field and nothing else.
     */
    val replyTo: Long?

    /** Mails, without their bodies, exactly as the listing reports them. */
    @Serializable
    @SerialName("mails")
    data class Mails(
        @SerialName("reply_to") override val replyTo: Long? = null,
        @SerialName("mails") val mails: List<MailResponse>,
        /** Mails matching the window this answer was cut out of, itself included. */
        @SerialName("total") val total: Int,
        /** The cursor the mails were read with, echoed so a client can tell what it answers. */
        @SerialName("before") val before: String? = null,
    ) : StackEvent

    /** What one mail is filed under, after a [StackCommand.SetTags]. */
    @Serializable
    @SerialName("mail_tags")
    data class MailTags(
        @SerialName("reply_to") override val replyTo: Long? = null,
        @SerialName("mail") val mail: String,
        @SerialName("tags") val tags: List<TagResponse>,
    ) : StackEvent

    /**
     * Every tag the caller has. Sent when the socket opens and again whenever they change, so a
     * screen never has to ask -- which is what keeps the autocomplete honest when a tag is made on
     * another screen, or by the agent.
     */
    @Serializable
    @SerialName("tags")
    data class Tags(
        @SerialName("tags") val tags: List<TagResponse>,
        @SerialName("reply_to") override val replyTo: Long? = null,
    ) : StackEvent

    /** A command that could not be carried out. The socket stays open. */
    @Serializable
    @SerialName("error")
    data class Failed(
        @SerialName("reply_to") override val replyTo: Long? = null,
        /** The `type` of the command, absent when the frame could not even be read. */
        @SerialName("command") val command: String? = null,
        @SerialName("message") val message: String,
    ) : StackEvent
}
