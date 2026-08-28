package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.ai.AgentLine
import es.jvbabi.overmail.server.ai.MAGIC_STEP
import es.jvbabi.overmail.server.ai.MailAnalyst
import es.jvbabi.overmail.server.ai.MailContext
import es.jvbabi.overmail.server.ai.MailDirection
import es.jvbabi.overmail.server.ai.ProposedTag
import es.jvbabi.overmail.server.ai.REVISION_STEP
import es.jvbabi.overmail.server.ai.SENDER_STEP
import es.jvbabi.overmail.server.ai.SenderAnalysis
import es.jvbabi.overmail.server.ai.TOPIC_STEP
import es.jvbabi.overmail.server.ai.ThreadKind
import es.jvbabi.overmail.server.ai.TopicTag
import es.jvbabi.overmail.server.ai.asTags
import es.jvbabi.overmail.server.ai.mailLinks
import es.jvbabi.overmail.server.ai.readableBody
import es.jvbabi.overmail.server.ai.MailParticipant as AiParticipant
import es.jvbabi.overmail.server.config.AiConfig
import es.jvbabi.overmail.server.domain.models.ClassificationReason
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailAiClassification
import es.jvbabi.overmail.server.domain.models.MagicEmailKind
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailAiClassificationRepository
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.MagicEmailRepository
import es.jvbabi.overmail.server.domain.repository.MailIdentifierRepository
import es.jvbabi.overmail.server.domain.repository.MemoryRepository
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.domain.repository.ThreadRepository
import es.jvbabi.overmail.server.domain.spam.toRuleFacts
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What the sender step came to, and what was filed off it. */
data class SenderOutcome(
    val reading: SenderAnalysis?,
    /** The tags it produced, under the names the mailbox actually spells them. */
    val filed: List<ProposedTag>,
    val failure: String?,
)

/** What the mail carries as a way into somewhere, and what was written down for it. */
data class MagicOutcome(
    val provider: String?,
    /** The payload per kind: the code as the mail writes it, the link whole. */
    val ways: Map<MagicEmailKind, String>,
    val validUntil: Instant?,
    val failure: String?,
)

/** What the mail is about, as proposals, plus what became of the matter it names. */
data class TopicOutcome(
    /** Proposed and not filed: the revision step decides which of these go on. */
    val proposals: List<TopicTag>,
    val identifier: String?,
    val kind: ThreadKind,
    /** What happened to the matter -- noted, opened, joined -- null where there is none. */
    val matter: MatterFiled?,
    val failure: String?,
)

/** What the revision step changed, and whether it got to decide at all. */
data class RevisionOutcome(
    val changes: List<String>,
    val said: String?,
    /** False where the step was not worth starting. */
    val ran: Boolean,
    /** True where the proposals were filed as proposed because the step never decided about them. */
    val fellBack: Boolean,
    val failure: String?,
)

/**
 * Somebody watching a run as it happens.
 *
 * Every method does nothing by default, because most callers want exactly that: a mail read off a
 * queue with nobody at the screen has no use for any of this, and the run is stored either way. What
 * this exists for is the one caller that *is* a screen -- see the agent socket, which turns each of
 * these into a frame -- and the point of the split is that the pipeline does not know the difference.
 */
interface ClassificationWatcher {
    /** One line of what was said, as it is said. */
    suspend fun line(line: AgentLine) {}

    suspend fun sender(outcome: SenderOutcome) {}
    suspend fun magic(outcome: MagicOutcome) {}
    suspend fun topic(outcome: TopicOutcome) {}
    suspend fun revision(outcome: RevisionOutcome) {}

    companion object {
        /** Nobody is watching, which is the ordinary case. */
        val NOBODY: ClassificationWatcher = object : ClassificationWatcher {}
    }
}

/**
 * Reads one mail and files it: the whole run, in the order the steps have to happen in.
 *
 * Its own class rather than the body of the socket it started as, because there are now three
 * reasons a mail gets read -- a reader pressing a button, a mail arriving, a stretch of the mailbox
 * being worked through -- and every one of them wants the same four steps in the same order with
 * the same things written down. What differs between them is only who is watching and what gets
 * written on the row, see [ClassificationReason], and both of those are arguments.
 *
 * The order is not an accident. The sender is read first because its tags are what the search in the
 * last step goes on; the magic step is next because it is cheap and independent; what the mail is
 * about is proposed third, from one mail alone; and the last step is the only one that looks at the
 * rest of the mailbox, which is why it needs everything the others wrote.
 *
 * Every run is stored, including the one that fell over, see [EmailAiClassificationRepository].
 */
class MailClassifier(
    private val analyst: MailAnalyst,
    private val emails: EmailRepository,
    private val tagging: TagRepository,
    private val threading: ThreadRepository,
    private val magic: MagicEmailRepository,
    private val matters: MailIdentifierRepository,
    private val remembering: MemoryRepository,
    private val classifications: EmailAiClassificationRepository,
    private val config: AiConfig,
) {

    /**
     * Runs the four steps over [email].
     *
     * Nothing comes back. What the run decided is in the mailbox by the time this returns -- the
     * tags, the threads, the memories -- and what it said is in the record it kept of itself. A
     * caller that wants to watch it happen passes a [watcher]; one that only wants it done passes
     * nothing.
     *
     * [reason] is what set it going, and it is stored rather than worked out: the same run looks
     * identical from in here whether a reader asked for it or a queue did.
     *
     * Throws what the steps throw, once the run has been recorded. A backend that is down is not
     * this class's problem to swallow -- a queue wants to know, and a socket wants to close.
     */
    suspend fun classify(
        email: Email,
        owner: User,
        reason: ClassificationReason,
        watcher: ClassificationWatcher = ClassificationWatcher.NOBODY,
    ) {
        val mailId = email.id

        // What the mailbox knows about its reader, as it stood when this mail was sent -- see
        // [Memories], where the stretch of time each one covers is the point. The summaries go into
        // the context of every step; whatever is known behind them is fetched by handle, and only by
        // the step that can decide it needs it.
        val memories = MemoryHandles(remembering.coreMemories(owner, at = email.sent))
        val context = email.asAnalysisContext(owner, memories.lines())

        // Kept as well as passed on, and kept in the order it happened: whatever a watcher was shown
        // is exactly what is written down.
        val history = mutableListOf<AgentLine>()
        val startedAt = Clock.System.now()

        suspend fun log(line: AgentLine) {
            history += line
            watcher.line(line)
        }

        try {
            // The lines go out from inside the step rather than after it, which is the whole point of
            // logging it: a step that hangs on a model that is not answering shows the prompt it is
            // hanging on.
            val origin = analyst.run(SENDER_STEP, context, ::log)

            // Filed as well as reported. Where a mail comes from is what a reader looks for by name
            // -- everything from the Sparkasse, everything that came through GitHub -- and it is the
            // one thing the tagging step is told not to name, because it is read here.
            val tagFiling = TagFiling(owner = owner, tagging = tagging)

            // The names as the mailbox spells them rather than as this reading spelled them: filing
            // goes through the one place that reuses a word the mailbox already has.
            val fromTags = tagFiling.file(mailId, origin.value?.asTags().orEmpty())

            // A step that could not be answered is not an error: it comes back with nothing in it
            // and a `failure` saying why, which is passed on the same way an answer would be.
            watcher.sender(SenderOutcome(origin.value, fromTags, origin.failure))

            val carried = analyst.run(MAGIC_STEP, context, ::log)
            val reading = carried.value

            // Both flags can be true, and on these mails usually are: the code is written out and a
            // link beside it carries the same code. That is two rows, one per kind, each with the
            // thing itself beside it -- a row that cannot say how to get in sends the reader back
            // into the mailbox it saved them from.
            //
            // The link comes out of the mail rather than out of the answer: the model names which of
            // the numbered links it is, and the link that number points at is the mail's own,
            // character for character. A number pointing nowhere and a code that did not come back
            // both mean no row for that kind.
            val ways = buildMap {
                if (reading?.carriesCode == true && reading.code != null) {
                    put(MagicEmailKind.CODE, reading.code)
                }

                val link = reading?.linkNumber?.let { context.links.getOrNull(it - 1) }
                if (reading?.carriesLink == true && link != null) put(MagicEmailKind.LINK, link)
            }

            // Against when the mail was sent, not against now: a code good for ten minutes was good
            // for ten minutes from the mail, and a mail read an hour after it arrived must not come
            // out as one that expires in ten.
            val validUntil = reading?.validForMinutes?.let { email.sent + it.minutes }
            val provider = reading?.provider

            // Filed without asking: whether a mail carries a code is a reading and not a judgement,
            // and a list of what still works is only worth having if it fills itself.
            if (provider != null) {
                for ((kind, payload) in ways) {
                    magic.record(mailId, provider, kind, payload, validUntil)
                }
            }

            watcher.magic(MagicOutcome(provider, ways, validUntil, carried.failure))

            val topic = analyst.run(TOPIC_STEP, context, ::log)
            val about = topic.value

            // Proposed and not filed. This step saw one mail, so what it thought of is a word for
            // that mail -- and a mailbox filled straight from here collects "Rechnung" next to
            // "Rechnungen" next to "Beleg" until nothing is findable under any of them.
            val proposedTags = about?.tags.orEmpty().map {
                ProposedTag(name = it.tag, reason = it.asReason())
            }

            // The identifier is filed in two steps that happen at two different times, see
            // [MatterFiling]: the tag and the record now, the thread once a second mail turns up.
            val identifier = about?.threadId?.takeIf { about.threadKind != ThreadKind.NONE }
            val filed = identifier?.let {
                MatterFiling(
                    owner = owner,
                    matters = matters,
                    threads = threading,
                    tagging = tagging,
                ).file(mailId, it, about.threadKind.germanName)
            }

            watcher.topic(
                TopicOutcome(
                    proposals = about?.tags.orEmpty(),
                    identifier = identifier,
                    kind = about?.threadKind ?: ThreadKind.NONE,
                    matter = filed,
                    failure = topic.failure,
                )
            )

            // The last step is the only one that looks at more than this mail, and the only one that
            // needed the ones before it to have run: it searches on what they wrote down.
            val desk = RevisionDesk(
                owner = owner,
                mailId = mailId,
                sentAt = email.sent,
                emails = emails,
                tagging = tagging,
                threading = threading,
                matters = matters,
                remembering = remembering,
                memories = memories,
                proposals = proposedTags,
            )

            // Null where there is nothing to search on, and then no request is spent finding out.
            val briefing = desk.briefing()
            val revision = briefing?.let {
                analyst.converse(REVISION_STEP, context, it, desk::run, ::log)
            }

            // The safety net under the proposals: a conversation that never got to the tags -- a
            // backend that is down, a model that talked its way past them -- must not cost the mail
            // its tags altogether. Filed as they were proposed then.
            val fellBack = !desk.hasSetTagsOn(mailId) && proposedTags.isNotEmpty()
            if (fellBack) tagFiling.file(mailId, proposedTags)

            watcher.revision(
                RevisionOutcome(
                    changes = desk.changes,
                    said = revision?.said,
                    ran = briefing != null,
                    fellBack = fellBack,
                    failure = revision?.failure,
                )
            )
        } finally {
            // Kept whether or not the run got to the end of itself. A screen that hangs up mid-run
            // cancels this coroutine, and a backend that never answers ends it with an exception --
            // and those are the two runs most worth having a record of. The write is shielded from
            // the cancellation that brought us here, or it would be cancelled on the spot;
            // `finished_at` is when it stopped, not when it finished.
            withContext(NonCancellable) { store(mailId, reason, history, startedAt) }
        }
    }

    /** The record of the run, written exactly once -- from the `finally` above and nowhere else. */
    private suspend fun store(
        mailId: Uuid,
        reason: ClassificationReason,
        history: List<AgentLine>,
        startedAt: Instant,
    ): EmailAiClassification = classifications.record(
        emailId = mailId,
        reason = reason,
        history = history,
        // Added up off the lines themselves: every request logged what it cost, which makes this the
        // one total that cannot disagree with the log beside it. Null rather than zero where no
        // request reported anything -- a local backend that counts nothing has not run a free model.
        tokensIn = history.mapNotNull { it.usage?.input }.takeIf { it.isNotEmpty() }?.sum(),
        tokensOut = history.mapNotNull { it.usage?.output }.takeIf { it.isNotEmpty() }?.sum(),
        provider = config.type,
        model = config.model,
        fastModel = config.fastModel,
        startedAt = startedAt,
        finishedAt = Clock.System.now(),
    )
}

/**
 * What a matter of this kind is called, for the title of a thread the agent opens.
 *
 * German, unlike the prompts, because it is not a wire format: it is stored as the thread's name and
 * shown to the reader as it stands. Plain words rather than the kind's own name, so a thread reads as
 * "Rechnung RE-2024-00123" and not as "INVOICE RE-2024-00123".
 *
 * [ThreadKind.NONE] cannot get here -- a thread is only opened where the mail carries an identifier
 * -- and is given the same word as anything unclear rather than an exception nobody would ever see.
 */
val ThreadKind.germanName: String
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

/**
 * Why the mail carries this tag, as one line for [TagRepository.attach].
 *
 * The sentence and the words it was read off together, because they answer different questions: the
 * sentence says what the agent made of the mail, the quote says what it was looking at. A reader who
 * disagrees with a tag wants the second one -- it is the part they can check.
 */
fun TopicTag.asReason(): String = "${reason.trim().trimEnd('.')}. Zitat: „${quote.trim()}“"

/**
 * The mail as the analysis steps see it, see [MailContext].
 *
 * The body starts as the one a rule is held against: the text part, or the HTML flattened -- a model
 * handed raw markup spends its attention on tags. What a step is handed is cut down further from
 * there, because a rule is matched for free and a model is not, see [readableBody].
 */
private fun Email.asAnalysisContext(owner: User, memories: List<String>): MailContext {
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
        // Everyone the mail names, cc and bcc included: who else was written to is part of reading it.
        recipients = recipients.map { AiParticipant(name = it.name, address = it.emailUser.address) },
        subject = subject,
        body = readableBody(flattened),
        links = mailLinks(flattened),
        memories = memories,
    )
}
