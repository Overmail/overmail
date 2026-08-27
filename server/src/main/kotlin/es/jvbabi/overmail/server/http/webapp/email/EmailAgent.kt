package es.jvbabi.overmail.server.http.webapp.email

import es.jvbabi.overmail.server.ai.AgentLine
import es.jvbabi.overmail.server.ai.MAGIC_STEP
import es.jvbabi.overmail.server.ai.MailAnalyst
import es.jvbabi.overmail.server.ai.MailContext
import es.jvbabi.overmail.server.ai.MailDirection
import es.jvbabi.overmail.server.ai.ProposedTag
import es.jvbabi.overmail.server.ai.REVISION_STEP
import es.jvbabi.overmail.server.ai.SENDER_STEP
import es.jvbabi.overmail.server.ai.TOPIC_STEP
import es.jvbabi.overmail.server.ai.ThreadKind
import es.jvbabi.overmail.server.ai.TopicTag
import es.jvbabi.overmail.server.ai.asTags
import es.jvbabi.overmail.server.ai.mailLinks
import es.jvbabi.overmail.server.ai.readableBody
import es.jvbabi.overmail.server.ai.MailParticipant as AiParticipant
import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.agent.MatterFiled
import es.jvbabi.overmail.server.domain.agent.MatterFiling
import es.jvbabi.overmail.server.domain.agent.RevisionDesk
import es.jvbabi.overmail.server.domain.agent.TagFiling
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.MagicEmailKind
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.MagicEmailRepository
import es.jvbabi.overmail.server.domain.repository.MailIdentifierRepository
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.domain.repository.ThreadRepository
import es.jvbabi.overmail.server.domain.spam.toRuleFacts
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
import kotlin.time.Duration.Companion.minutes
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
            val magicEmailRepository = dependencies.resolve<MagicEmailRepository>()
            val mailIdentifierRepository = dependencies.resolve<MailIdentifierRepository>()
            val tagRepository = dependencies.resolve<TagRepository>()
            val threadRepository = dependencies.resolve<ThreadRepository>()
            val analyst = dependencies.resolve<MailAnalyst>()

            // Read once, and with it the check that the mail is the caller's. A socket that is
            // going to hand a mail to a model reads it here, before anybody is let in, rather than
            // trusting the id it was opened with.
            val email = emailRepository.getById(mailId).first()
            if (email == null || email.imapAccount.user.id != user.id) {
                return@webSocket close(CloseReason(CloseReason.Codes.NORMAL, "no such mail"))
            }

            // Nobody asked, and nobody is going to: whoever would have is gone.
            if (!awaitReadMail()) return@webSocket

            val context = email.asAnalysisContext(user)

            // The framing is the socket's own: a run that happens with nobody watching has no use
            // for a start and an end, it just returns.
            sendEvent(EmailAgentEvent.AgentStarted)

            // The lines go out from inside the step rather than after it, which is the whole point
            // of logging it: a step that hangs on a model that is not answering shows the prompt it
            // is hanging on. The log is called on this coroutine, so a send that suspends holds the
            // step up -- which is what keeps the lines in the order they happened.
            val origin = analyst.run(SENDER_STEP, context) { line -> sendEvent(line.asEvent()) }

            // Filed as well as reported. Where a mail comes from is what a reader looks for by
            // name -- everything from the Sparkasse, everything that came through GitHub -- and it
            // is the one thing the tagging step is told not to name, because it is read here. See
            // `asTags`, which decides which of the fields is a label somebody would file under.
            val tagFiling = TagFiling(owner = user, tagging = tagRepository)

            // The names as the mailbox spells them rather than as this reading spelled them: filing
            // goes through the one place that reuses a word the mailbox already has, see [TagFiling].
            val fromTags = tagFiling.file(mailId, origin.value?.asTags().orEmpty()).map {
                EmailAgentEvent.FiledTag(tag = it.name, reason = it.reason)
            }

            // A step that could not be answered is not an error: it comes back with nothing in it
            // and a `failure` saying why, which goes out the same way an answer would.
            sendEvent(
                EmailAgentEvent.Sender(
                    person = origin.value?.person,
                    organisation = origin.value?.organisation,
                    via = origin.value?.via,
                    context = origin.value?.context.orEmpty(),
                    tags = fromTags,
                    failure = origin.failure,
                )
            )

            val magic = analyst.run(MAGIC_STEP, context) { line -> sendEvent(line.asEvent()) }

            val reading = magic.value

            // Both flags can be true, and on these mails usually are: the code is written out and
            // a link beside it carries the same code. That is two rows, one per kind, which is what
            // the table is shaped for -- each with the thing itself beside it, because a row that
            // cannot say how to get in sends the reader back into the mailbox it saved them from.
            //
            // The link comes out of the mail rather than out of the answer: the model names which
            // of the numbered links it is, see `MagicAnalysis.linkNumber`, and the link that number
            // points at is the mail's own, character for character. A number pointing nowhere and a
            // code that did not come back both mean no row for that kind -- `validate` has already
            // refused the answers where these disagree, so this is what stands between a step that
            // failed anyway and the table.
            val ways = buildMap {
                if (reading?.carriesCode == true && reading.code != null) {
                    put(MagicEmailKind.CODE, reading.code)
                }

                val link = reading?.linkNumber?.let { context.links.getOrNull(it - 1) }
                if (reading?.carriesLink == true && link != null) put(MagicEmailKind.LINK, link)
            }

            // Against when the mail was sent, not against now: a code good for ten minutes was good
            // for ten minutes from the mail, and a mail read an hour after it arrived must not come
            // out as one that expires in ten. Null stays null -- see `valid_until`, a stated end is
            // the only kind worth writing down.
            val validUntil = reading?.validForMinutes?.let { email.sent + it.minutes }

            // Filed without asking: whether a mail carries a code is a reading and not a
            // judgement, and a list of what still works is only worth having if it fills itself.
            // Nothing comes back from this -- a row that was already there is the ordinary answer
            // on a rerun, see `record`.
            val provider = reading?.provider
            if (provider != null) {
                for ((kind, payload) in ways) {
                    magicEmailRepository.record(mailId, provider, kind, payload, validUntil)
                }
            }

            sendEvent(
                EmailAgentEvent.Magic(
                    provider = provider,
                    kinds = ways.keys.map { it.name.lowercase() },
                    validUntil = validUntil?.toString(),
                    failure = magic.failure,
                )
            )

            val topic = analyst.run(TOPIC_STEP, context) { line -> sendEvent(line.asEvent()) }
            val about = topic.value

            // Proposed and not filed. This step saw one mail, so what it thought of is a word for
            // that mail -- and a mailbox filled straight from here collects "Rechnung" next to
            // "Rechnungen" next to "Beleg" until nothing is findable under any of them. The step
            // that looks at the mail which came before this one decides what actually goes on, with
            // the mailbox's own vocabulary in front of it, see [REVISION_STEP]. What happens when
            // that step cannot run is below: the proposals are filed as they stand.
            val proposedTags = about?.tags.orEmpty().map {
                ProposedTag(name = it.tag, reason = it.asReason())
            }

            // The identifier is what lets a mail join a matter nobody has grouped yet, and it is
            // filed in two steps that happen at two different times -- see [MatterFiling]: the tag
            // and the record now, the thread once a second mail turns up. A kind without an
            // identifier does not happen -- the step refuses that answer -- and is skipped here
            // rather than trusted.
            val identifier = about?.threadId?.takeIf { about.threadKind != ThreadKind.NONE }
            val filed = identifier?.let {
                MatterFiling(
                    owner = user,
                    matters = mailIdentifierRepository,
                    threads = threadRepository,
                    tagging = tagRepository,
                ).file(mailId, it, about.threadKind.germanName)
            }

            sendEvent(
                EmailAgentEvent.Topic(
                    // The identifier's own tag among them, which is the sharpest one a mail gets:
                    // every mail about that invoice writes that string and nothing else does.
                    tags = about?.tags.orEmpty().map {
                        EmailAgentEvent.FiledTag(tag = it.tag, reason = it.reason, quote = it.quote)
                    },
                    // The identifier's own tag is filed rather than proposed: it is the one label
                    // with nothing to weigh about it. No other mail writes that string, so there is
                    // no existing word for it to be reconciled with.
                    filedTags = listOfNotNull(
                        filed?.let { EmailAgentEvent.FiledTag(tag = it.tag, reason = it.reason) }
                    ),
                    identifier = identifier,
                    identifierKind = identifier?.let { about.threadKind.name.lowercase() },
                    matter = filed?.asWireWord(),
                    failure = topic.failure,
                )
            )

            // The last step is the only one that looks at more than this mail, and the only one
            // that needed the ones before it to have run: it searches on what they wrote down. What
            // it does is its own -- see the desk, which is what actually carries the tools out and
            // what holds the line that the agent only ever changes what the agent made.
            val desk = RevisionDesk(
                owner = user,
                mailId = mailId,
                sentAt = email.sent,
                emails = emailRepository,
                tagging = tagRepository,
                threading = threadRepository,
                matters = mailIdentifierRepository,
                proposals = proposedTags,
            )

            // Null where the mail carries neither a tag nor an identifier: nothing to search on, so
            // nothing to revise, and no request spent finding that out.
            val briefing = desk.briefing()
            val revision = briefing?.let {
                analyst.converse(REVISION_STEP, context, it, desk::run) { line -> sendEvent(line.asEvent()) }
            }

            // The safety net under the proposals: a conversation that never got to the tags -- a
            // backend that is down, a model that talked its way past them -- must not cost the mail
            // its tags altogether. Filed as they were proposed then, which is what the mailbox used
            // to do with them anyway.
            val fellBack = !desk.hasSetTagsOn(mailId) && proposedTags.isNotEmpty()
            if (fellBack) tagFiling.file(mailId, proposedTags)

            sendEvent(
                EmailAgentEvent.Revision(
                    changes = desk.changes,
                    said = revision?.said,
                    ran = briefing != null,
                    proposalsFiledAsProposed = fellBack,
                    failure = revision?.failure,
                )
            )

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
 * Why the mail carries this tag, as one line for [TagRepository.attach].
 *
 * The sentence and the words it was read off together, because they answer different questions: the
 * sentence says what the agent made of the mail, the quote says what it was looking at. A reader
 * who disagrees with a tag wants the second one -- it is the part they can check.
 */
private fun TopicTag.asReason(): String = "${reason.trim().trimEnd('.')}. Zitat: „${quote.trim()}“"

/** What became of a matter, in the word the wire uses for it. */
private fun MatterFiled.asWireWord(): String = when (this) {
    is MatterFiled.Noted -> "noted"
    is MatterFiled.Opened -> "opened"
    is MatterFiled.Joined -> "joined"
}

/**
 * What a matter of this kind is called, for the title of a thread the agent opens.
 *
 * German, unlike everything else here, because it is not a wire format: it is stored as the thread's
 * name and shown to the reader as it stands. Plain words rather than the kind's own name, so a
 * thread reads as "Rechnung RE-2024-00123" and not as "INVOICE RE-2024-00123".
 *
 * [ThreadKind.NONE] cannot get here -- a thread is only opened where the mail carries an identifier
 * -- and is given the same word as anything unclear rather than an exception nobody would ever see.
 */
private val ThreadKind.germanName: String
    get() = when (this) {
        ThreadKind.INVOICE -> "Rechnung"
        ThreadKind.ORDER -> "Bestellung"
        ThreadKind.BOOKING -> "Buchung"
        ThreadKind.SHIPMENT -> "Sendung"
        ThreadKind.TICKET -> "Ticket"
        ThreadKind.TRANSACTION -> "Zahlung"
        ThreadKind.ISSUE -> "Ticket"
        ThreadKind.CONVERSATION -> "Unterhaltung"
        ThreadKind.OTHER, ThreadKind.NONE -> "Vorgang"
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

/**
 * The mail as the analysis steps see it, see [MailContext].
 *
 * The body starts as the one a rule is held against: the text part, or the HTML flattened -- a
 * model handed raw markup spends its attention on tags, see `mailFactsOf`. What a step is handed
 * is cut down further from there, because a rule is matched for free and a model is not, see
 * [readableBody].
 */
private fun Email.asAnalysisContext(owner: User): MailContext {
    // Flattened once and read twice: the body a step is handed is cut down from it, and the whole
    // links are picked out of it before that cut takes them down to their hosts.
    val flattened = toRuleFacts().body

    return MailContext(
        owner = AiParticipant(name = owner.name, address = owner.email),
        direction = MailDirection.of(
            ownerAddress = owner.email,
            senderAddress = sender.address,
            recipientAddresses = recipients.map { it.emailUser.address },
        ),
        sender = AiParticipant(name = senderName, address = sender.address),
        // Everyone the mail names, cc and bcc included: who else was written to is part of reading
        // it.
        recipients = recipients.map { AiParticipant(name = it.name, address = it.emailUser.address) },
        subject = subject,
        body = readableBody(flattened),
        links = mailLinks(flattened),
    )
}
