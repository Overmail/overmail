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
import es.jvbabi.overmail.server.domain.models.TaggedMail
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.domain.repository.ThreadRepository
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Walks the mailbox from the oldest mail to the newest and hands every mail to the AI once.
 *
 * The queue is the order the repository hands out plus the set of mails already seen: newly
 * imported mails simply appear at the end of that order and are picked up on the next emission.
 * That set lives in memory, so a restart runs the whole mailbox through again -- fine while this
 * only prints, and the point where a processed marker on the mail will have to go.
 */
class AiProcessingQueue(
    private val emailRepository: EmailRepository,
    private val tagRepository: TagRepository,
    private val threadRepository: ThreadRepository,
    /** Runs the analysis steps a mail is put through, configured from the `ai` section. */
    private val analyzer: MailAnalyzer,
) {

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

    private val processed = mutableSetOf<Uuid>()

    /** Runs until the surrounding scope is cancelled. */
    suspend fun start() {
        // Everything the agent filed goes first, so a run starts from what a human left behind and
        // the whole mailbox is classified afresh. This is here to make the pipeline testable, and
        // it is the first thing to take out once its results are meant to survive a restart.
        val tags = tagRepository.clearAgentWork()
        val threads = threadRepository.clearAgentWork()
        println(
            "Cleared ${tags.links} agent filings and ${tags.created} agent tags," +
                " ${threads.links} thread memberships and ${threads.created} agent threads"
        )

        emailRepository
            .getAllIdsOldestFirst()
            .collect { ids ->
                // Sequential on purpose: mails are handled in the order they were sent, and the
                // model call this is going to become should not fan out over the whole mailbox.
                ids.filterNot { it in processed }.forEach { id ->
                    val email = emailRepository.getById(id).first() ?: return@forEach
                    // A mail that could not be processed stays unmarked and is picked up again on
                    // the next emission: a model that is down should not silently skip the inbox.
                    try {
                        process(email)
                    } catch (cause: Exception) {
                        println("Processing failed for '${email.subject}': ${cause.message}")
                        return@forEach
                    }
                    processed += id
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
            review(email, context, suggested, neighbours, placement)
        }

        filed.forEach { suggestion ->
            file(email.id, email.imapAccount.user, suggestion)
            println("  ${suggestion.tag}: ${suggestion.reason}")
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
        val threadsByMail = threadRepository.threadsOf(user, neighbours.map { it.id })

        val start = Clock.System.now()
        val answer = analyzer.run(MailThreadStep, context, threadMaterial(neighbours, threadsByMail))
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
        // any of them already sits in one, that is the thread.
        val existing = sameMatter.firstNotNullOfOrNull { threadsByMail[it.id] }
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
        neighbours: List<TaggedMail>,
        placement: ThreadPlacement?,
    ): List<MailTag> {
        val start = Clock.System.now()
        val review = analyzer.run(
            MailTagReviewStep,
            context,
            tagReviewMaterial(suggested, neighbours, placement),
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
