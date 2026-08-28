package es.jvbabi.overmail.server.http.webapp.email

import es.jvbabi.overmail.server.ai.AgentLine
import es.jvbabi.overmail.server.ai.AgentRole
import es.jvbabi.overmail.server.ai.MAGIC_STEP
import es.jvbabi.overmail.server.ai.REVISION_STEP
import es.jvbabi.overmail.server.ai.SENDER_STEP
import es.jvbabi.overmail.server.ai.TOPIC_STEP
import es.jvbabi.overmail.server.ai.ThreadKind
import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.agent.ClassificationWatcher
import es.jvbabi.overmail.server.domain.agent.MagicOutcome
import es.jvbabi.overmail.server.domain.agent.MailClassifier
import es.jvbabi.overmail.server.domain.agent.MatterFiled
import es.jvbabi.overmail.server.domain.agent.RevisionOutcome
import es.jvbabi.overmail.server.domain.agent.SenderOutcome
import es.jvbabi.overmail.server.domain.agent.TopicOutcome
import es.jvbabi.overmail.server.domain.models.ClassificationReason
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** `GET /api/webapp/email/{id}/agent` as a websocket. */
fun Route.emailAgent() {

    authenticate(SESSION_AUTH) {
        /**
         * One agent run over one mail: it lets a reader in, waits for them to ask for the mail to
         * be read, reads it and hangs up.
         *
         * Its own socket rather than a corner of [emailDetail], because it is the opposite kind of
         * thing. That one is a subscription -- it never asks anything, never ends, and sends what
         * it sends whoever is listening. This one is a piece of work somebody started: it exists
         * because a button was pressed, it runs once, and when the work is done there is nothing
         * left to hold open. Sharing a socket meant the two got in each other's way, and it is what
         * put a run in a coroutine beside the collect where the ordering had to be argued about.
         *
         * The ask is waited for rather than assumed. Opening a mail is not the same as wanting a
         * model run over it -- a run costs seconds and a request -- so nothing happens until the
         * screen says so. A rerun is a second socket, which is also what makes cancelling one free:
         * the screen hangs up and the session takes the run with it.
         *
         * Everything here happens on the one coroutine, in order, and that is the point: the wait
         * is the handler itself, the run is the handler itself, and the socket lives exactly as
         * long as the work does.
         *
         * The steps are run from here and what they answer is put on the wire from here, rather
         * than through anything that reports a run as it goes. Each of them is a function that
         * takes a mail and gives back a data class, none of them has ever heard of a socket, and
         * that is what lets the next caller -- a mail being read as it arrives, with nobody
         * watching -- call exactly the same thing. What is decided in here is only what a run looks
         * like on the wire.
         *
         * Four steps. Three of them read this mail and nothing else: where it is from, see
         * [SENDER_STEP], whether it is a way into somewhere, see [MAGIC_STEP], and what it is
         * about, see [TOPIC_STEP]. The fourth is a different kind of thing -- see [REVISION_STEP],
         * which is handed tools and the mailbox, looks up what came before this mail and puts right
         * what a step seeing one mail could not know. It runs last because it runs on what the
         * others wrote.
         *
         * What they do with what they read differs on purpose. The origin is reported and nothing
         * more: it is already on the mail, and what to make of it is the reader's business. A code
         * or a sign-in link is written down without asking -- that is a reading, not an opinion, and
         * a list of what still works is only worth having if it fills itself. The tags and the
         * matter are written down too, and that one is a decision: a mailbox nobody sorts is not
         * sorted, and a proposal waiting to be accepted is a second inbox. What makes it bearable is
         * that each row says why it is there, in the mail's own words, and comes off in one click.
         *
         * What the mailbox knows about its reader is read against the mail's own date rather than
         * against today: a mail from 2022 is read against what was true in 2022, see [Memories].
         * The summaries go into every step's context, the detail behind them only where the last
         * step asks for it -- which is what keeps a mailbox that knows forty things about somebody
         * affordable to read one mail with.
         *
         * The whole run is kept, whatever it came to, see [EmailAiClassificationRepository]: every
         * prompt, every answer, every tool call, what it cost and which models it cost it at. A
         * mailbox that files itself is only worth trusting if the filing can be read back, and this
         * is the only thing that answers the question a wrong tag raises. It is written down as
         * `MANUAL`, because this socket exists exactly when somebody pressed a button.
         *
         * A mail of somebody else closes the socket like one that does not exist -- which ids are
         * taken is not the caller's business.
         */
        webSocket("/{id}/agent") {
            // Inside `authenticate` there is a user, or the handshake never got here.
            val user = call.principal<User>()
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated"))

            val mailId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "mail is not an id"))

            val dependencies = call.application.dependencies
            val emailRepository = dependencies.resolve<EmailRepository>()
            val classifier = dependencies.resolve<MailClassifier>()

            // Read once, and with it the check that the mail is the caller's. A socket that is
            // going to hand a mail to a model reads it here, before anybody is let in, rather than
            // trusting the id it was opened with.
            val email = emailRepository.getById(mailId).first()
            if (email == null || email.imapAccount.user.id != user.id) {
                return@webSocket close(CloseReason(CloseReason.Codes.NORMAL, "no such mail"))
            }

            // Nobody asked, and nobody is going to: whoever would have is gone.
            if (!awaitReadMail()) return@webSocket

            // The framing is the socket's own: a run that happens with nobody watching has no use
            // for a start and an end, it just returns.
            sendEvent(EmailAgentEvent.AgentStarted)

            // Everything the run does is the classifier's; everything this socket does is turn what
            // it reports into frames. Which is the point of the split: the queue runs exactly the
            // same four steps with nobody watching, see [MailClassifier].
            //
            // The watcher is called on this coroutine, so a send that suspends holds the run up --
            // which is what keeps the frames in the order the run happened in.
            try {
                classifier.classify(email, user, ClassificationReason.MANUAL, asWatcher())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Exception) {
                // The run is recorded before it gets here. The screen is told the same way it is
                // told about a step that failed: a socket that dies without a word leaves a button
                // spinning for good.
                sendEvent(
                    EmailAgentEvent.AgentMessage(
                        step = "run",
                        attempt = 1,
                        role = AgentRole.ERROR.name.lowercase(),
                        text = cause.message ?: cause::class.simpleName ?: "the run stopped",
                    )
                )
            }

            sendEvent(EmailAgentEvent.AgentFinished)
        }
    }
}

/**
 * Waits for the screen to ask for the mail to be read. False where it never did, because whoever
 * would have asked hung up first.
 *
 * Frames that are not the ask are dropped rather than answered: this socket has no error channel,
 * and one unreadable frame is no reason to hang up on a screen that is otherwise fine.
 */
private suspend fun DefaultWebSocketServerSession.awaitReadMail(): Boolean {
    while (true) {
        val command = try {
            receiveDeserialized<EmailAgentCommand>()
        } catch (_: ClosedReceiveChannelException) {
            // The screen went away before it asked. Nothing to clean up: the session ends with it.
            return false
        } catch (_: WebsocketDeserializeException) {
            // A frame the converter could not take at all, e.g. a binary one.
            continue
        } catch (_: SerializationException) {
            // Readable, but not a command.
            continue
        }

        // Exhaustive on purpose, though there is one command: a second one has to be decided about
        // here rather than quietly read as this one.
        when (command) {
            is EmailAgentCommand.ReadMail -> return true
        }
    }
}

/**
 * What the screen is sent, told apart by `type` as on the stack's socket: a run arrives as it
 * happens once one was asked for -- a start, a line per thing said, the readings it made, and an
 * end.
 */
@Serializable
sealed interface EmailAgentEvent {

    /**
     * The agent is starting. Everything logged before it belongs to a run that is gone, so a screen
     * showing the log throws it away when this arrives.
     */
    @Serializable
    @SerialName("agent_started")
    data object AgentStarted : EmailAgentEvent

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
    ) : EmailAgentEvent

    /**
     * Where the agent reads the mail as coming from -- the fields of
     * [es.jvbabi.overmail.server.ai.SenderAnalysis], one for one. Every one of them can come back
     * empty and every one is meant to; `failure` says why there is nothing at all when the model
     * could not be asked.
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
        /**
         * What of it was filed as tags, and why -- see
         * [es.jvbabi.overmail.server.ai.asTags]. Not every field becomes one, and none of them does
         * on the mail nobody could read anything off.
         */
        @SerialName("tags") val tags: List<FiledTag> = emptyList(),
        @SerialName("failure") val failure: String? = null,
    ) : EmailAgentEvent

    /**
     * What the mail carries as a way into somewhere, see
     * [es.jvbabi.overmail.server.ai.MagicAnalysis].
     *
     * Said as well as written, unlike the readings that are only reported: the rows are already in
     * the table by the time this goes out, and this is the screen being told what landed there
     * rather than being asked about it.
     *
     * `kinds` is empty for the great majority of mail, which is not one of these, and empty as well
     * where the step could not be run -- `failure` is what tells those two apart.
     */
    @Serializable
    @SerialName("magic_email")
    data class Magic(
        /** Who it lets the reader into, absent for a mail that lets nobody in. */
        @SerialName("provider") val provider: String? = null,
        /** `code`, `link`, or both. */
        @SerialName("kinds") val kinds: List<String> = emptyList(),
        /** When it stops working, absent where the mail never stated a span. */
        @SerialName("valid_until") val validUntil: String? = null,
        @SerialName("failure") val failure: String? = null,
    ) : EmailAgentEvent

    /**
     * One tag a run attached, and why it is on the mail.
     *
     * Shared by the readings that file tags, because the screen does one thing with them whichever
     * step wrote them: shows the label, and the reason behind it when somebody asks why.
     */
    @Serializable
    data class FiledTag(
        @SerialName("tag") val tag: String,
        /** What it was read off, in one sentence. */
        @SerialName("reason") val reason: String,
        /** The words of the mail behind it, where the step quoted any. */
        @SerialName("quote") val quote: String? = null,
    )

    /**
     * What the mail is about, and what ties it to the rest of its matter -- see
     * [es.jvbabi.overmail.server.ai.TopicAnalysis].
     *
     * Said as well as written, like the magic rows above: the tags are attached and the thread is
     * joined by the time this goes out. It is here so a screen can show what the run made of the
     * mail before the mail's own socket has caught up with the rows -- and so it can say why, which
     * the tag list on a mail does not carry.
     */
    @Serializable
    @SerialName("topic_analysis")
    data class Topic(
        /**
         * What was proposed for the mail, the most general first, and not yet on it: the revision
         * step decides which of these are really filed, see [Revision].
         */
        @SerialName("tags") val tags: List<FiledTag> = emptyList(),
        /** What was filed outright, which is the identifier's own tag and nothing else. */
        @SerialName("filed_tags") val filedTags: List<FiledTag> = emptyList(),
        /** The identifier of the matter, absent for the great majority of mail. */
        @SerialName("identifier") val identifier: String? = null,
        /** What kind of identifier it is: `invoice`, `order`, `conversation`, ... */
        @SerialName("identifier_kind") val identifierKind: String? = null,
        /**
         * What became of the matter: `noted` for the first mail about it, which is written down and
         * tagged but gets no thread yet, `opened` for the second, which is where the thread comes
         * from, and `joined` for every one after that. Absent for a mail that names no matter.
         */
        @SerialName("matter") val matter: String? = null,
        @SerialName("failure") val failure: String? = null,
    ) : EmailAgentEvent

    /**
     * What the last step changed about the mailbox, see
     * [es.jvbabi.overmail.server.ai.REVISION_STEP].
     *
     * Changes and not a reading: this step does not answer anything, it works. What is here is the
     * list of what it did, in the reader's language, and it is empty far more often than not --
     * leaving a mailbox alone is the ordinary outcome of looking at it.
     *
     * `ran` is false where the step was not worth starting: a mail with no tags and no identifier
     * gives nothing to search its earlier company by.
     */
    @Serializable
    @SerialName("revision")
    data class Revision(
        /** What was changed, one line each. Empty where nothing was. */
        @SerialName("changes") val changes: List<String> = emptyList(),
        /** The model's closing sentence, absent where it never got to one. */
        @SerialName("said") val said: String? = null,
        @SerialName("ran") val ran: Boolean = true,
        /**
         * True where the step never decided about the proposed tags and they were filed as they
         * were proposed -- the mail keeps its tags, nobody checked them against the mailbox.
         */
        @SerialName("proposals_filed_as_proposed") val proposalsFiledAsProposed: Boolean = false,
        @SerialName("failure") val failure: String? = null,
    ) : EmailAgentEvent

    /**
     * The run is over, however it went.
     *
     * Its own event rather than a flag on the last reading: which step happens to be last is not
     * something a screen should have to know, and there is going to be more than one.
     */
    @Serializable
    @SerialName("agent_finished")
    data object AgentFinished : EmailAgentEvent
}

/**
 * What the screen sends up, told apart by `type` as on the stack's socket.
 *
 * One command, which is the whole reason this socket takes anything at all: the agent runs when it
 * is asked to and not before.
 */
@Serializable
sealed interface EmailAgentCommand {

    /**
     * Reads this mail and files it: where it is from, see [SENDER_STEP], whether it lets its reader
     * in somewhere, see [MAGIC_STEP], what it is about, see [TOPIC_STEP], and finally what that
     * means for the mail already in the mailbox, see [REVISION_STEP].
     *
     * One command for all of them rather than one each. They are wanted together -- a reader
     * pressing the button wants the mail read, not a step of it -- and running them from one place
     * is what keeps the order and the log a single sequence. A screen that wants one step on its own
     * is a reason to add a command then, not to guess at one now.
     */
    @Serializable
    @SerialName("read_mail")
    data object ReadMail : EmailAgentCommand
}

/**
 * The socket as somebody watching the run: every step's outcome turned into the frame for it.
 *
 * Here and not in the classifier, because this is the only part of a run that is about a screen. What
 * a step decided is the same whoever asked for it; what a screen is told about it is this socket's
 * business, and the queue's runs are not told to anybody at all.
 */
private fun DefaultWebSocketServerSession.asWatcher() = object : ClassificationWatcher {

    override suspend fun line(line: AgentLine) = sendEvent(line.asEvent())

    override suspend fun sender(outcome: SenderOutcome) {
        sendEvent(
            EmailAgentEvent.Sender(
                person = outcome.reading?.person,
                organisation = outcome.reading?.organisation,
                via = outcome.reading?.via,
                context = outcome.reading?.context.orEmpty(),
                tags = outcome.filed.map { EmailAgentEvent.FiledTag(tag = it.name, reason = it.reason) },
                failure = outcome.failure,
            )
        )
    }

    override suspend fun magic(outcome: MagicOutcome) {
        sendEvent(
            EmailAgentEvent.Magic(
                provider = outcome.provider,
                kinds = outcome.ways.keys.map { it.name.lowercase() },
                validUntil = outcome.validUntil?.toString(),
                failure = outcome.failure,
            )
        )
    }

    override suspend fun topic(outcome: TopicOutcome) {
        sendEvent(
            EmailAgentEvent.Topic(
                tags = outcome.proposals.map {
                    EmailAgentEvent.FiledTag(tag = it.tag, reason = it.reason, quote = it.quote)
                },
                // The identifier's own tag is filed rather than proposed: it is the one label with
                // nothing to weigh about it. No other mail writes that string, so there is no
                // existing word for it to be reconciled with.
                filedTags = listOfNotNull(
                    outcome.matter?.let { EmailAgentEvent.FiledTag(tag = it.tag, reason = it.reason) }
                ),
                identifier = outcome.identifier,
                identifierKind = outcome.identifier?.let { outcome.kind.name.lowercase() },
                matter = outcome.matter?.asWireWord(),
                failure = outcome.failure,
            )
        )
    }

    override suspend fun revision(outcome: RevisionOutcome) {
        sendEvent(
            EmailAgentEvent.Revision(
                changes = outcome.changes,
                said = outcome.said,
                ran = outcome.ran,
                proposalsFiledAsProposed = outcome.fellBack,
                failure = outcome.failure,
            )
        )
    }
}

/** What became of a matter, in the word the wire uses for it. */
private fun MatterFiled.asWireWord(): String = when (this) {
    is MatterFiled.Noted -> "noted"
    is MatterFiled.Opened -> "opened"
    is MatterFiled.Joined -> "joined"
}

/** One log line on the wire. The role goes out lowercase, like every other `type` here. */
private fun AgentLine.asEvent() = EmailAgentEvent.AgentMessage(
    step = step,
    attempt = attempt,
    role = role.name.lowercase(),
    text = text,
    inputTokens = usage?.input,
    outputTokens = usage?.output,
)

/**
 * Sends an event as an [EmailAgentEvent] rather than as whatever it happens to be. The type
 * argument is load bearing: it is what puts the `type` on the wire, and a subclass would go out
 * without one.
 */
private suspend fun DefaultWebSocketServerSession.sendEvent(event: EmailAgentEvent) {
    sendSerialized<EmailAgentEvent>(event)
}
