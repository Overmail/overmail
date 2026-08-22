package es.jvbabi.overmail.server.jobs.processor

import es.jvbabi.overmail.server.ai.MailAnalyzer
import es.jvbabi.overmail.server.ai.MailContext
import es.jvbabi.overmail.server.ai.MailParticipant
import es.jvbabi.overmail.server.ai.TokenUsage
import es.jvbabi.overmail.server.ai.steps.MailOriginStep
import es.jvbabi.overmail.server.ai.steps.MailTag
import es.jvbabi.overmail.server.ai.steps.MailTagReviewStep
import es.jvbabi.overmail.server.ai.steps.MailTagsStep
import es.jvbabi.overmail.server.ai.steps.MailThreadStep
import es.jvbabi.overmail.server.ai.steps.ThreadPlacement
import es.jvbabi.overmail.server.ai.steps.threadMaterial
import es.jvbabi.overmail.server.ai.steps.normalised
import es.jvbabi.overmail.server.ai.steps.tagReviewMaterial
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.TaggedMail
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.domain.repository.ThreadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Walks the mailbox from the oldest mail to the newest and hands every mail to the AI once.
 *
 * The queue is what the repository hands out: every mail without a processing timestamp, oldest
 * first. A mail is stamped once it has been through, so newly imported mails appear at the end of
 * the next emission and a restart picks up where the last run left off instead of classifying the
 * whole mailbox afresh.
 */
class AiProcessingQueue(
    private val emailRepository: EmailRepository,
    private val tagRepository: TagRepository,
    private val threadRepository: ThreadRepository,
    /** Runs the analysis steps a mail is put through, configured from the `ai` section. */
    private val analyzer: MailAnalyzer,
    private val coroutineScope: CoroutineScope,
) {

    // Volatile: a request thread reads and writes this, the queue itself runs on another.
    @Volatile
    private var queueJob: Job? = null

    private companion object {
        /**
         * How much of the body the agent gets to see. Who wrote a mail stands at its top, and a
         * newsletter can run to tens of thousands of characters that a local model would chew on
         * for nothing.
         */
        const val BODY_LIMIT = 2000

        /** How many already filed mails the review gets to see. */
        const val NEIGHBOURS = 10
    }

    /** Whether the queue is working through the mailbox right now. */
    val isRunning: Boolean get() = queueJob?.isActive == true

    /** Starts working through the mailbox, or leaves the run that is already going alone. */
    fun start() {
        if (isRunning) return
        queueJob = coroutineScope.launch { run() }
    }

    /**
     * Puts the queue down. The mail in the model's hands right now is dropped where it is: it
     * stays unstamped, so the next [start] picks it up again.
     *
     * Waits for that mail to actually be let go, so a caller that clears the agent's work
     * afterwards cannot have it written again behind their back.
     */
    suspend fun stop() {
        queueJob?.cancelAndJoin()
        queueJob = null
    }

    /** Runs until the job is cancelled or the surrounding scope goes. */
    private suspend fun run() {
        emailRepository
            .getUnprocessedIdsOldestFirst()
            .collect { ids ->
                // Sequential on purpose: mails are handled in the order they were sent, and the
                // model call this is going to become should not fan out over the whole mailbox.
                ids.forEach { id ->
                    val email = emailRepository.getById(id).first() ?: return@forEach
                    // A mail that could not be processed stays unstamped and is picked up again on
                    // the next emission: a model that is down should not silently skip the inbox.
                    try {
                        process(email)
                    } catch (cause: Exception) {
                        println("Processing failed for '${email.subject}': ${cause.message}")
                        return@forEach
                    }
                    emailRepository.markAiProcessed(id)
                }
            }
    }

    private suspend fun process(email: Email) {
        println("Processing: ${email.subject}")
        val start = Clock.System.now()
        // Assembled once and handed to every step: the steps differ in what they ask, not in what
        // they are looking at.
        val context = MailContext(
            // The mailbox this mail sits in: the name off the account owner, the address the
            // account fetches with. Together they let a step recognise the user on a mail.
            owner = MailParticipant(
                name = email.imapAccount.user.name,
                address = email.imapAccount.username,
            ),
            sender = MailParticipant(email.senderName, email.sender.address),
            recipients = email.recipients.map { MailParticipant(it.name, it.emailUser.address) },
            subject = email.subject,
            body = email.textContent.orEmpty().take(BODY_LIMIT),
        )

        // What the mail already carries: a run that was cut short before it stamped the mail
        // leaves its tags behind, and a user may have filed the mail themselves. Handed to the
        // review as context rather than thrown away -- that is where it gets tidied up.
        val alreadyFiled = tagRepository.getForEmail(email).first()
        if (alreadyFiled.isNotEmpty()) {
            println("  already filed under: ${alreadyFiled.joinToString { it.tag.name }}")
        }

        val sender = analyzer.run(MailOriginStep, context)
        val senderIdentificationAt = Clock.System.now()

        val origin = sender.value

        // Thinking is counted in characters: the backends report no token count of their own for
        // it, and whatever was spent on thinking is already part of the output count.
        println(
            "[SENDER]: took ${(senderIdentificationAt - start).inWholeSeconds}s," +
                " inout/thinking/output: ${sender.usage.report()}" +
                " ${origin.person}@${origin.institution}"
        )

        val tags = analyzer.run(MailTagsStep, context)
        val taggedAt = Clock.System.now()

        println(
            "[TAGS]: took ${(taggedAt - senderIdentificationAt).inWholeSeconds}s," +
                " inout/thinking/output: ${tags.usage.report()}"
        )
        val suggested = tags.value.normalised().tags

        // Mails around this one: filed under the same tags -- identifiers included, they are tags
        // too -- or carrying the same subject under a reply prefix, which is the other mail of a
        // conversation.
        val neighbours = tagRepository
            .findNeighbours(
                user = email.imapAccount.user,
                tagNames = suggested.map { it.tag },
                subject = email.subject,
                before = email.sent,
                limit = NEIGHBOURS,
            )
            .first()

        // The thread runs before the review: mails of one matter should end up filed alike, and
        // the review can only see that once it knows which of the neighbours this mail continues.
        // Nothing to continue without neighbours -- a thread needs a second mail.
        val placement = if (neighbours.isEmpty()) null else thread(email, context, neighbours)

        val filed = if (neighbours.isEmpty()) {
            suggested
        } else {
            review(email, context, suggested, alreadyFiled, neighbours, placement)
        }

        filed.forEach { suggestion ->
            file(email.id, email.imapAccount.user, suggestion)
            println("  ${suggestion.tag}: ${suggestion.reason}")
        }

        // The answer is the complete filing of this mail, so what the agent had put there and this
        // run does not name again comes off: an interrupted run must not leave its tags behind. A
        // tag a user attached is not the agent's to take off, `detachAgentTag` sees to that.
        val kept = filed.map { it.tag.trim().lowercase() }.toSet()
        alreadyFiled
            .filter { it.createdByAgent && it.tag.name.lowercase() !in kept }
            .forEach { stale ->
                if (tagRepository.detachAgentTag(email.id, stale.tag.id)) {
                    println("  - ${stale.tag.name} (left over)")
                }
            }
    }

    /**
     * Puts the mail into the matter it continues, opening one when the mails call for it. Returns
     * where it ended up, or null when it stands alone.
     */
    private suspend fun thread(
        email: Email,
        context: MailContext,
        neighbours: List<TaggedMail>,
    ): ThreadPlacement? {
        val user = email.imapAccount.user
        // The mail itself is asked for along with the neighbours: an interrupted run may have put
        // it into a thread already, and that should not turn into a second one.
        val threads = threadRepository.threadsOf(user, neighbours.map { it.id } + email.id)
        val ownThread = threads[email.id]
        val threadsByMail = threads - email.id

        val start = Clock.System.now()
        val answer = analyzer.run(
            MailThreadStep,
            context,
            threadMaterial(neighbours, threadsByMail, ownThread),
        )
        val decidedAt = Clock.System.now()

        val choice = answer.value
        // Numbers outside the list are mails the model invented.
        val sameMatter = choice.sameMatter.mapNotNull { neighbours.getOrNull(it - 1) }.distinctBy { it.id }

        println(
            "[THREAD]: took ${(decidedAt - start).inWholeSeconds}s," +
                " inout/thinking/output: ${answer.usage.report()}," +
                " ${sameMatter.size} of ${neighbours.size} same matter"
        )

        if (sameMatter.isEmpty()) return null

        // Whether this joins or opens a thread follows from those mails, not from the model: if
        // any of them already sits in one, that is the thread. Failing that, the thread this mail
        // was left in by an earlier run serves, so a retry continues it instead of opening a second.
        val existing = sameMatter.firstNotNullOfOrNull { threadsByMail[it.id] } ?: ownThread
        val thread = existing ?: threadRepository.create(
            user = user,
            // A title is what the step is asked for, but a missing one must not cost the thread.
            title = choice.title?.takeIf { it.isNotBlank() } ?: email.subject.withoutReplyPrefixes(),
            createdByAgent = true,
        )

        threadRepository.attach(email.id, thread, choice.reason, createdByAgent = true)
        println("  ${if (existing == null) "+>" else "->"} ${thread.title}: ${choice.reason}")

        sameMatter
            .filter { threadsByMail[it.id]?.id != thread.id }
            .forEach { mail ->
                threadRepository.attach(mail.id, thread, choice.reason, createdByAgent = true)
                println("     ${mail.subject}")
            }

        choice.betterTitle?.takeIf { it.isNotBlank() && it != thread.title }?.let { title ->
            // Renames only what the agent named itself; a title from a user stays.
            if (threadRepository.retitleAgentThread(thread.id, title)) println("  ~> $title")
        }

        return ThreadPlacement(
            thread = thread,
            memberIds = (sameMatter.map { it.id } + threadsByMail.filterValues { it.id == thread.id }.keys).toSet(),
        )
    }

    /**
     * Second pass over the suggestion with the neighbouring mails in view, which may also correct
     * how those were filed. Returns the tags for the mail at hand.
     */
    private suspend fun review(
        email: Email,
        context: MailContext,
        suggested: List<MailTag>,
        alreadyFiled: List<EmailTag>,
        neighbours: List<TaggedMail>,
        placement: ThreadPlacement?,
    ): List<MailTag> {
        val start = Clock.System.now()
        val review = analyzer.run(
            MailTagReviewStep,
            context,
            tagReviewMaterial(suggested, alreadyFiled, neighbours, placement),
        )
        val reviewedAt = Clock.System.now()

        println(
            "[REVIEW]: took ${(reviewedAt - start).inWholeSeconds}s," +
                " inout/thinking/output: ${review.usage.report()}," +
                " ${neighbours.size} neighbours"
        )

        review.value.corrections.forEach { correction ->
            // The model answers with the numbers the mails were listed under, so anything outside
            // that range is a mail it invented.
            val neighbour = neighbours.getOrNull(correction.mail - 1) ?: return@forEach

            correction.remove.forEach { name ->
                val filed = neighbour.tags.firstOrNull { it.tag.name.equals(name, ignoreCase = true) }
                // Only the agent's own filing comes off, and only where it exists.
                if (filed == null || !filed.createdByAgent) return@forEach

                if (tagRepository.detachAgentTag(neighbour.id, filed.tag.id)) {
                    println("  [${correction.mail}] - ${filed.tag.name}")
                }
            }

            correction.add.forEach { suggestion ->
                file(neighbour.id, email.imapAccount.user, suggestion)
                println("  [${correction.mail}] + ${suggestion.tag}: ${suggestion.reason}")
            }
        }

        return review.value.tags.ifEmpty { suggested }
    }

    /** Files one mail under one suggested tag, creating the tag on first use. */
    private suspend fun file(emailId: Uuid, user: User, suggestion: MailTag) {
        val tag = tagRepository.findOrCreate(
            user = user,
            name = suggestion.tag,
            createdByAgent = true,
        )

        tagRepository.attach(
            emailId = emailId,
            tag = tag,
            reason = suggestion.reason,
            createdByAgent = true,
        )
    }
}

/** Not every backend reports what a request cost. */
private fun Int?.orDash(): String = this?.toString() ?: "-"

/** The triple the log line carries: tokens in, thinking, tokens out. */
private fun TokenUsage.report(): String =
    "${input.orDash()} tok/${reasoningCharacters.orDash()} chars/${output.orDash()} tok"

/** `Re:`, `AW:`, `Fwd:` and friends, so a fallback title is the matter and not the reply to it. */
private val REPLY_PREFIX = Regex("""^\s*(re|aw|antw|fwd|fw|wg)\s*(\[\d+])?\s*:\s*""", RegexOption.IGNORE_CASE)

private fun String.withoutReplyPrefixes(): String {
    var subject = trim()
    while (true) {
        val stripped = subject.replaceFirst(REPLY_PREFIX, "")
        if (stripped == subject) return subject.take(255)
        subject = stripped
    }
}
