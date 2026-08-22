package es.jvbabi.overmail.server.ai

import kotlinx.serialization.KSerializer

/**
 * How hard a step is, and therefore what it is worth running on. Extraction that only has to read
 * what is written can take the fast model; a step that has to weigh or interpret gets the capable
 * one. Which model each tier resolves to is [MailAnalyzer]'s business.
 */
enum class ModelTier {
    /** Reading facts straight out of the mail. */
    FAST,

    /** Judgement calls: intent, relevance, what to do about a mail. */
    CAPABLE,
}

/**
 * One specialised look at a mail: its own prompt, its own output schema, its own model.
 *
 * Steps are never merged. Every one of them answers a single question ([MailOrigin] answers who
 * sent the mail and nothing else), which is what keeps a prompt short enough to be reliable, lets
 * a step be re-prompted or swapped without touching the others, and lets each pick the model it
 * actually needs. [SHARED_RULES] are prepended to every step's [instructions].
 */
class MailAnalysisStep<T>(
    /** Identifies the prompt, and shows up in Koog's traces. */
    val id: String,

    /** What this step is to extract, on top of [SHARED_RULES]. */
    val instructions: String,

    /** Schema of the answer: Koog turns it into the JSON schema the model has to fill. */
    val serializer: KSerializer<T>,

    val tier: ModelTier = ModelTier.FAST,

    /**
     * Hard ceiling on the answer. A step knows roughly how long its own answer is, and a model
     * that starts deliberating instead of answering should cost a failed request rather than
     * minutes of a queue that runs over the whole mailbox.
     */
    val maxOutputTokens: Int = 600,
) {
    /**
     * What the step sends as its system prompt. The shared rules come first: they are the ones a
     * model is most likely to drift from, and `/no_think` only takes effect at the very top.
     */
    val systemPrompt: String get() = "$SHARED_RULES\n\n$instructions"
}

/**
 * The house rules every analysis step inherits.
 *
 * `/no_think` switches the reasoning of a thinking model off: these steps read facts out of a mail
 * rather than reason about them, and the thinking is what makes a local model slow.
 */
private val SHARED_RULES = """
    /no_think
    You analyse a single email. You are given the mailbox owner, the sender, the recipients, the
    subject and the body, and you answer one specific question about that mail.

    Rules for every answer:
    - Use only what the mail itself shows: its text, its signature, its addresses.
    - Never invent, guess or complete information that is not in the mail. When something is not
      there, the answer is null -- an empty field is a correct answer, not a failure.
    - Prefer explicit evidence: what the mail writes out beats what it seems to imply.
    - Two mails of the same kind must get the same answer.
    - Answer with the requested structure only. No explanation, no reasoning, no extra fields.
""".trimIndent()
