package es.jvbabi.overmail.server.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who said one line of a run.
 *
 * The three roles a request is made of, plus the one nobody sent: a backend that did not answer,
 * or answered something unusable twice, is part of the log rather than something that quietly
 * ends it.
 */
@Serializable
enum class AgentRole {
    /** The step's instructions, shared rules included -- what went out as the system prompt. */
    @SerialName("system")
    SYSTEM,

    /** The mail, and on a repeat attempt what was wrong with the answer before it. */
    @SerialName("user")
    USER,

    /** What came back, as text, before anything tried to parse it. */
    @SerialName("assistant")
    ASSISTANT,

    /**
     * A tool the model asked for, with the arguments it wrote, see `MailToolStep`. Its own role
     * rather than part of what the model said: it is the model acting rather than answering, and on
     * a step that changes the mailbox that is the line a reader is looking for.
     */
    @SerialName("tool_call")
    TOOL_CALL,

    /** What that tool answered, or why it refused. */
    @SerialName("tool_result")
    TOOL_RESULT,

    /**
     * What the model thought, where it reports that apart from what it said.
     *
     * Its own role because it is not the answer -- except on the backends where it is: LM Studio
     * hands a thinking model's whole completion back as reasoning and leaves the content empty,
     * and then this is the only place the answer ever appears. See `answerText`.
     */
    @SerialName("thinking")
    THINKING,

    /** Why there is no answer. */
    @SerialName("error")
    ERROR,
}

/**
 * One line of a run, in the order it happened.
 *
 * A record of what was actually sent and what actually came back, not a summary of it: a step that
 * answers nonsense is only debuggable if the prompt that produced it can be read back verbatim.
 *
 * Serialisable because a run is kept: the whole conversation is stored with the classification it
 * produced, see [es.jvbabi.overmail.server.domain.models.EmailAiClassification]. The names are
 * spelled out rather than left to the compiler, because these end up in a column and a field renamed
 * in here would otherwise quietly stop matching what is already stored.
 */
@Serializable
data class AgentLine(
    /** Which step said it, so a log over several steps reads as several conversations. */
    @SerialName("step") val step: String,

    /**
     * 1 for the first ask, and one more for each ask that carries a complaint about the answer
     * before it -- see `MAX_ATTEMPTS`, which is where the asking stops.
     */
    @SerialName("attempt") val attempt: Int,

    @SerialName("role") val role: AgentRole,

    @SerialName("text") val text: String,

    /** What the request cost, on the [AgentRole.ASSISTANT] line that answered it. */
    @SerialName("usage") val usage: TokenUsage? = null,
)

/**
 * Where a run's lines go.
 *
 * Called in order and on the coroutine the run is on, so a listener that suspends holds the run
 * up -- which is exactly what a socket writing the lines out as they happen wants: the log fills
 * in while the model works instead of arriving in one piece at the end.
 */
typealias AgentLog = suspend (AgentLine) -> Unit
