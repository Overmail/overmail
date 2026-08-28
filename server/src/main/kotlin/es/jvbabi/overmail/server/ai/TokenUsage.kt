package es.jvbabi.overmail.server.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What one agent run cost, as the model reported it. Null where the backend does not say.
 *
 * Serialisable for the same reason [AgentLine] is: it is stored with the run it belongs to.
 */
@Serializable
data class TokenUsage(
    @SerialName("input") val input: Int?,

    /** Everything the model wrote, thinking included: Ollama counts it in one number. */
    @SerialName("output") val output: Int?,

    /**
     * Length of the thinking the model returned. In characters, not tokens -- Ollama reports no
     * separate count for it, only the text.
     */
    @SerialName("reasoning_characters") val reasoningCharacters: Int?,
) {
    /**
     * Two runs of one step, added up. A step that had to be asked again cost both requests, and
     * the log line is about the step rather than about a single attempt.
     */
    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        input = input and other.input,
        output = output and other.output,
        reasoningCharacters = reasoningCharacters and other.reasoningCharacters,
    )
}

/** Counts add up where both sides have one; a backend that reports nothing stays silent. */
private infix fun Int?.and(other: Int?): Int? = when {
    this == null -> other
    other == null -> this
    else -> this + other
}
