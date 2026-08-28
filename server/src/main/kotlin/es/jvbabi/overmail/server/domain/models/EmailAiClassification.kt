package es.jvbabi.overmail.server.domain.models

import es.jvbabi.overmail.server.ai.AgentLine
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What set an agent run going. */
enum class ClassificationReason {
    /** A mail was fetched and read as it arrived, with nobody watching. */
    AUTOMATIC_INCOMING,

    /** Somebody had the mailbox, or a stretch of it, worked through in one go. */
    BULK_PROCESS,

    /** A reader asked for this one mail to be read, which is what the detail screen's button does. */
    MANUAL,
}

/**
 * One run of the agent over one mail: everything it was asked, everything it answered, what that
 * cost and which backend it cost it at, see
 * [es.jvbabi.overmail.server.database.models.EmailAiClassifications].
 *
 * A record of a run and not of a conclusion. What the run decided is in the mailbox -- the tags, the
 * threads, the rows a reader sees -- and this is how it came to be decided: the prompts verbatim,
 * the answers unparsed, the tools it called and what they told it. Which is the only thing that
 * answers the question a wrong tag raises. Why did it do that.
 *
 * Several per mail, and on purpose: a mail read again is a second run, and the first one is not
 * overwritten. What changed between two readings of the same mail is worth being able to look at.
 */
data class EmailAiClassification(
    val id: Uuid,
    val emailId: Uuid,
    /** What set the run going. */
    val reason: ClassificationReason,
    /** Every line of it, in the order it happened. */
    val history: List<AgentLine>,
    /** What the whole run cost, added up over its requests. Null where the backend counted nothing. */
    val tokensIn: Int?,
    val tokensOut: Int?,
    /** Which API the backend speaks: `openai`, `ollama`. */
    val provider: String,
    /** The model the steps that weigh something ran on. */
    val model: String,
    /**
     * The model the steps that only read facts ran on, where the config names a separate one.
     *
     * Null where it does not, which means those steps ran on [model] too. Kept apart because a run
     * really does use two models, and a row claiming one would make the cheap steps look like the
     * expensive ones.
     */
    val fastModel: String?,
    val startedAt: Instant,
    /** When it stopped, however it stopped. */
    val finishedAt: Instant,
)
