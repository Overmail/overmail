package es.jvbabi.overmail.server.jobs.processor

import es.jvbabi.overmail.server.ai.MailAnalyzer
import es.jvbabi.overmail.server.ai.MailContext
import es.jvbabi.overmail.server.ai.MailParticipant
import es.jvbabi.overmail.server.ai.steps.MailOriginStep
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.repository.EmailRepository
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
    }

    private val processed = mutableSetOf<Uuid>()

    /** Runs until the surrounding scope is cancelled. */
    suspend fun start() {
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

        val answer = analyzer.run(MailOriginStep, context)

        val senderIdentificationAt = Clock.System.now()

        val origin = answer.value
        val usage = answer.usage

        // Thinking is counted in characters: Ollama reports no token count of its own for it, and
        // whatever it spent on thinking is already part of the output count.
        println(
            "[SENDER]: took ${(senderIdentificationAt - start).inWholeSeconds}s," +
                " inout/thinking/output: ${usage.input.orDash()} tok/${usage.reasoningCharacters.orDash()} chars/${usage.output.orDash()} tok" +
                " ${origin.person}@${origin.institution}"
        )
    }
}

/** Not every backend reports what a request cost. */
private fun Int?.orDash(): String = this?.toString() ?: "-"
