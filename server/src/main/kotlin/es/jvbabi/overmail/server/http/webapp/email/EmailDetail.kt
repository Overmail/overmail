package es.jvbabi.overmail.server.http.webapp.email

import es.jvbabi.overmail.server.ai.AgentLine
import es.jvbabi.overmail.server.ai.MailAnalyst
import es.jvbabi.overmail.server.ai.MailContext
import es.jvbabi.overmail.server.ai.MailDirection
import es.jvbabi.overmail.server.ai.SENDER_STEP
import es.jvbabi.overmail.server.ai.SenderAnalysis
import es.jvbabi.overmail.server.ai.readableBody
import es.jvbabi.overmail.server.ai.MailParticipant as AiParticipant
import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.SpamEntry
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.SpamRepository
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.http.mails.MailResponse
import es.jvbabi.overmail.server.domain.spam.toRuleFacts
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
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
         * The agent does not run on its own. It runs when the screen asks, once per `analyse`
         * command, because a model costs seconds and a request per mail somebody merely opened --
         * and opening a mail is not the same as wanting it read. Everything it is asked and
         * everything it answers goes back down as it happens, which for now is here to be read
         * rather than to be used. The names it reads are the exception: those are filed as tags,
         * see [fileNames].
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
            val tagRepository = dependencies.resolve<TagRepository>()

            // Read once, and with it the check that the mail is the caller's: the flows below
            // filter by user too, but the agent is handed the mail itself and must not be handed
            // somebody else's.
            val email = emailRepository.getById(mailId).first()
            if (email == null || email.imapAccount.user.id != user.id) {
                return@webSocket close(CloseReason(CloseReason.Codes.NORMAL, "no such mail"))
            }

            val context = email.asAnalysisContext(user)

            // The run in flight, so the next one can wait for it to actually be gone. Written
            // only by the command loop, which is a single coroutine reading one frame at a time --
            // so there is nothing here to guard.
            var run: Job? = null

            /**
             * Runs the agent over the mail, cancelling whatever was still running.
             *
             * Joined rather than just cancelled: a run that is dropped mid-request is somewhere
             * inside a send, and letting it finish that send would drop one of its lines into the
             * log of the run that replaced it.
             */
            suspend fun start() {
                run?.cancelAndJoin()
                // Its own coroutine: a model takes seconds, and neither the state below nor the
                // next command must wait for it.
                run = launch {
                    analyse(analyst, tagRepository, emailRepository, spamRepository, user, mailId, context)
                }
            }

            // What the screen sends up. Its own coroutine, because the collect below never
            // returns; it ends with the session, which is the scope it is launched in.
            launch {
                while (true) {
                    val command = try {
                        receiveDeserialized<EmailDetailCommand>()
                    } catch (_: ClosedReceiveChannelException) {
                        // The screen went away. Nothing to clean up: the session ends with it.
                        return@launch
                    } catch (_: WebsocketDeserializeException) {
                        // A frame the converter could not take at all, e.g. a binary one.
                        continue
                    } catch (_: SerializationException) {
                        // Readable but not a command. Dropped rather than answered: this socket
                        // has no error channel, and one unreadable frame is no reason to hang up.
                        continue
                    }

                    when (command) {
                        is EmailDetailCommand.Analyse -> start()
                    }
                }
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
 * arrive on opening and on every change, and an agent run arrives as it happens once one is asked
 * for -- a start, a line per thing said, and the reading it ended with.
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
     * The agent is starting over. Everything logged before it belongs to a run that is gone, so a
     * screen showing the log throws it away when this arrives.
     */
    @Serializable
    @SerialName("agent_started")
    data object AgentStarted : EmailDetailEvent

    /**
     * One line of what the agent was asked and what it said, as it happens -- see [AgentLine].
     * The prompts verbatim and the answer unparsed: this is a log to read, not a result to use.
     */
    @Serializable
    @SerialName("agent_message")
    data class AgentMessage(
        @SerialName("step") val step: String,
        @SerialName("attempt") val attempt: Int,
        /** `system`, `user`, `assistant`, `thinking` or `error`. */
        @SerialName("role") val role: String,
        @SerialName("text") val text: String,
        /** What the request cost, on the answer that came back; absent where nothing was counted. */
        @SerialName("input_tokens") val inputTokens: Int? = null,
        @SerialName("output_tokens") val outputTokens: Int? = null,
    ) : EmailDetailEvent

    /**
     * Where the agent reads the mail as coming from. Every field can come back empty and every one
     * is meant to, see [es.jvbabi.overmail.server.ai.SenderAnalysis]; `failure` says why there is
     * nothing when the model could not be asked at all.
     *
     * Also what ends a run: the log above stops here, so this is what a screen waits for before
     * it lets anyone start another one.
     */
    @Serializable
    @SerialName("sender_analysis")
    data class Sender(
        @SerialName("person") val person: String? = null,
        @SerialName("organisation") val organisation: String? = null,
        /** The platform the mail came through, absent for mail that is simply mail. */
        @SerialName("via") val via: String? = null,
        /** Handles on what the mail belongs to: `gh:acme/widgets#412`, a newsletter's name. */
        @SerialName("context") val context: List<String> = emptyList(),
        @SerialName("failure") val failure: String? = null,
    ) : EmailDetailEvent
}

/**
 * What the screen sends up the socket, told apart by `type` as on the stack's socket.
 *
 * Only one thing so far, and it is the whole reason this socket takes anything at all: the agent
 * runs when it is asked to and not before. Everything about the mail itself is sent down on its own.
 */
@Serializable
sealed interface EmailDetailCommand {

    /**
     * Runs the agent over this mail, from the top, however many times it is asked.
     *
     * The mail is not re-read on a second ask -- it is the same mail, and what a rerun is for is
     * the model: a prompt that was just changed, or a backend that was not up a minute ago.
     */
    @Serializable
    @SerialName("analyse")
    data object Analyse : EmailDetailCommand
}

/**
 * One agent run, written out to the screen line by line while it happens.
 *
 * The lines are sent from inside the run rather than collected and sent at the end, which is the
 * whole point of logging it: a step that hangs on a model that is not answering shows the prompt
 * it is hanging on.
 */
private suspend fun DefaultWebSocketServerSession.analyse(
    analyst: MailAnalyst,
    tagRepository: TagRepository,
    emailRepository: EmailRepository,
    spamRepository: SpamRepository,
    user: User,
    mailId: Uuid,
    context: MailContext,
) {
    sendEvent(EmailDetailEvent.AgentStarted)

    val analysis = analyst.run(SENDER_STEP, context) { line -> sendEvent(line.asEvent()) }

    // Before the answer goes out, so the screen is told the run is done only once what the run
    // wrote is written.
    val filed = analysis.value?.let { fileNames(tagRepository, user, mailId, it) } == true

    // Said outright rather than left to the change stream, exactly as the stack's socket says the
    // tag list after it writes one: this socket is the one that filed them, so it knows without
    // having to be told, and the screen is right even where nothing is watching the write ahead
    // log. Only when something was actually written -- a rerun that files nothing new has nothing
    // to report, and `attach` writing nothing is the normal case on every rerun.
    if (filed) sendMailState(emailRepository, spamRepository, user, mailId)

    sendEvent(
        EmailDetailEvent.Sender(
            person = analysis.value?.person,
            organisation = analysis.value?.organisation,
            via = analysis.value?.via,
            context = analysis.value?.context.orEmpty(),
            failure = analysis.failure,
        )
    )
}

/**
 * The mail and its state as they stand now, for a change this socket made itself.
 *
 * The same event the socket's collect sends, read the same way, so a screen cannot tell them apart
 * -- which is the point: it is one kind of event, whoever had the news first.
 */
private suspend fun DefaultWebSocketServerSession.sendMailState(
    emailRepository: EmailRepository,
    spamRepository: SpamRepository,
    user: User,
    mailId: Uuid,
) {
    val summary = emailRepository
        .getSummariesForUser(user, limit = 1, ids = listOf(mailId))
        .first()
        .mails
        .firstOrNull()
        ?: return

    sendEvent(
        EmailDetailEvent.Mail(
            mail = summary.toResponse(),
            spam = spamRepository.getEntriesForEmail(mailId).first().lastOrNull().toResponse(),
        )
    )
}

/**
 * Files the names the agent read as tags on the mail, making the ones the user does not have yet.
 *
 * Returns whether anything was actually written, so a caller can tell a run that filed something
 * from one that found it all there already.
 *
 * Three of the four fields, and not [SenderAnalysis.context]: a person, an organisation and a
 * platform are things a reader would sort mail by, while a handle is something to match on.
 *
 * Nothing here is undone on a rerun, and nothing has to be. A tag the user already has is found
 * rather than made a second time, without regard to case, and a mail already filed under one is
 * left alone -- `attach` writes nothing then, which is what keeps a reason the reader wrote from
 * being replaced by one the agent came up with. A rerun that reads the mail differently adds what
 * it read; it does not take back what the run before it thought, because a tag on a mail is the
 * reader's to remove.
 */
private suspend fun fileNames(
    tagRepository: TagRepository,
    user: User,
    mailId: Uuid,
    analysis: SenderAnalysis,
): Boolean {
    // The reason is stored with the filing and is the agent's answer for it, see `attach`.
    val read = listOfNotNull(
        analysis.person?.withReason("Vom Agenten als Person hinter dieser Mail gelesen"),
        analysis.organisation?.withReason("Vom Agenten als Organisation hinter dieser Mail gelesen"),
        analysis.via?.withReason("Vom Agenten als Plattform gelesen, über die die Mail kam"),
    )

    var filed = false
    for ((name, reason) in read) {
        val tag = tagRepository.findOrCreate(user, name, createdByAgent = true)
        // Null for a mail already filed under it, which is what every rerun after the first sees.
        if (tagRepository.attach(mailId, tag, reason = reason, createdByAgent = true) != null) {
            filed = true
        }
    }

    return filed
}

/** A name worth filing, paired with why it was. Blank is not one, however it got here. */
private fun String.withReason(reason: String): Pair<String, String>? =
    takeIf { it.isNotBlank() }?.let { it to reason }

/** One log line on the wire. The role goes out lowercase, like every other `type` here. */
private fun AgentLine.asEvent() = EmailDetailEvent.AgentMessage(
    step = step,
    attempt = attempt,
    role = role.name.lowercase(),
    text = text,
    inputTokens = usage?.input,
    outputTokens = usage?.output,
)

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
 * The body starts as the one a rule is held against: the text part, or the HTML flattened -- a
 * model handed raw markup spends its attention on tags, see `mailFactsOf`. What a step is handed
 * is cut down further from there, because a rule is matched for free and a model is not, see
 * [readableBody].
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
    body = readableBody(toRuleFacts().body),
)
