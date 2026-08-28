package es.jvbabi.overmail.server.database.models

import es.jvbabi.overmail.server.domain.models.ClassificationReason
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * One run of the agent over one mail, kept whole.
 *
 * The point of the table is the [history]: a mailbox that files itself is only trustworthy if the
 * filing can be read back, and "why does this mail say Bewerbung" is answered by the conversation
 * that decided it and by nothing else. The counts and the model beside it are what make a run
 * comparable to the next one -- a prompt that got worse, a backend that got slower, a model swapped
 * for a cheaper one all show up here before they show up as a complaint.
 *
 * Several rows per mail. A mail read again is a second run: the earlier one stands, because what
 * changed between two readings is exactly what somebody would want to look at.
 */
object EmailAiClassifications : UuidTable("email_ai_classification") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)

    /** What set the run going -- a reader asking for it, an arrival, a sweep over the mailbox. */
    val reason = enumerationByName<ClassificationReason>("reason", 32)

    /**
     * The whole conversation as JSON: a line per thing said, in the order it happened, see
     * [es.jvbabi.overmail.server.ai.AgentLine].
     *
     * One column rather than a table of lines. Nothing queries inside it -- a run is read whole, by
     * somebody looking at one mail -- and a row per line would multiply the biggest thing here (the
     * prompts, which are thousands of characters each) by the number of times it is logged.
     *
     * `text` rather than `jsonb`: it is stored and handed back, never filtered on. The day something
     * wants to ask questions of it is the day to change the type, and the JSON will already be there.
     */
    val history = text("history")

    /**
     * What the run cost, added up over every request it made. Null where the backend reported
     * nothing -- which is a thing local backends do, and is not the same as zero.
     */
    val tokensIn = integer("tokens_in").nullable()
    val tokensOut = integer("tokens_out").nullable()

    /** Which API the backend speaks: `openai`, `ollama`. As the config writes it. */
    val provider = varchar("provider", 64)

    /** The model the steps that weigh something ran on. */
    val model = varchar("model", 128)

    /**
     * The model the reading steps ran on, null where the config names no separate one.
     *
     * Both, because a run uses both: recording one model would make a run look cheaper or dearer
     * than it was, and the first question about an odd classification is which model produced it.
     */
    val fastModel = varchar("fast_model", 128).nullable()

    val startedAt = timestamp("started_at")

    /** When it stopped, however it stopped -- finished, failed, or hung up on. */
    val finishedAt = timestamp("finished_at")

    init {
        // The mail, newest run first, is the one way this is read.
        index(isUnique = false, email, startedAt)
    }
}
