package es.jvbabi.overmail.server.ai

/**
 * Who said one line of a run.
 *
 * The three roles a request is made of, plus the one nobody sent: a backend that did not answer,
 * or answered something unusable twice, is part of the log rather than something that quietly
 * ends it.
 */
enum class AgentRole {
    /** The step's instructions, shared rules included -- what went out as the system prompt. */
    SYSTEM,

    /** The mail, and on a repeat attempt what was wrong with the answer before it. */
    USER,

    /** What came back, as text, before anything tried to parse it. */
    ASSISTANT,

    /**
     * A tool the model asked for, with the arguments it wrote, see `MailToolStep`. Its own role
     * rather than part of what the model said: it is the model acting rather than answering, and on
     * a step that changes the mailbox that is the line a reader is looking for.
     */
    TOOL_CALL,

    /** What that tool answered, or why it refused. */
    TOOL_RESULT,

    /**
     * What the model thought, where it reports that apart from what it said.
     *
     * Its own role because it is not the answer -- except on the backends where it is: LM Studio
     * hands a thinking model's whole completion back as reasoning and leaves the content empty,
     * and then this is the only place the answer ever appears. See `answerText`.
     */
    THINKING,

    /** Why there is no answer. */
    ERROR,
}

/**
 * One line of a run, in the order it happened.
 *
 * A record of what was actually sent and what actually came back, not a summary of it: a step that
 * answers nonsense is only debuggable if the prompt that produced it can be read back verbatim.
 */
data class AgentLine(
    /** Which step said it, so a log over several steps reads as several conversations. */
    val step: String,

    /**
     * 1 for the first ask, and one more for each ask that carries a complaint about the answer
     * before it -- see `MAX_ATTEMPTS`, which is where the asking stops.
     */
    val attempt: Int,

    val role: AgentRole,

    val text: String,

    /** What the request cost, on the [AgentRole.ASSISTANT] line that answered it. */
    val usage: TokenUsage? = null,
)

/**
 * Where a run's lines go.
 *
 * Called in order and on the coroutine the run is on, so a listener that suspends holds the run
 * up -- which is exactly what a socket writing the lines out as they happen wants: the log fills
 * in while the model works instead of arriving in one piece at the end.
 */
typealias AgentLog = suspend (AgentLine) -> Unit
